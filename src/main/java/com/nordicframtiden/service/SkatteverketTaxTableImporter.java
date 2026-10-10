package com.nordicframtiden.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.nordicframtiden.service.model.TaxTableRow;

/** Downloads and imports Skatteverket's public monthly payroll tax tables. */
@Component
@ConditionalOnProperty(
    prefix = "app.skatteverket.tax-table-import",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SkatteverketTaxTableImporter {
  private static final Logger log = LoggerFactory.getLogger(SkatteverketTaxTableImporter.class);
  private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");
  private static final Pattern DOWNLOAD_LINK = Pattern.compile(
      "href=\"([^\"]*allmanna-tabeller-manad\\.txt[^\"]*)\"", Pattern.CASE_INSENSITIVE);
  static final int EXPECTED_TABLE_COUNT = 14;
  private static final int MINIMUM_ROW_COUNT = 7_000;
  private static final int COLUMN_FIELD_START = 19;
  private static final int COLUMN_FIELD_WIDTH = 5;

  private final TaxTableImportStore store;
  private final HttpClient httpClient;
  private final URI indexUri;
  private final URI oneTimeTableUri;

  @Autowired
  public SkatteverketTaxTableImporter(
      TaxTableImportStore store,
      @Value("${app.skatteverket.tax-table-import.index-url}") URI indexUri,
      @Value("${app.skatteverket.tax-table-import.one-time-url}") URI oneTimeTableUri,
      @Value("${app.skatteverket.tax-table-import.timeout-seconds:30}") long timeoutSeconds) {
    this(store, indexUri, oneTimeTableUri, HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build());
  }

  SkatteverketTaxTableImporter(TaxTableImportStore store, URI indexUri, URI oneTimeTableUri, HttpClient httpClient) {
    this.store = store;
    this.indexUri = indexUri;
    this.oneTimeTableUri = oneTimeTableUri;
    this.httpClient = httpClient;
  }

  private com.nordicframtiden.config.ClusterJobLock jobLock;

  /** Cross-instance lock (Cloud Run may run several instances); absent in unit tests. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setJobLock(com.nordicframtiden.config.ClusterJobLock jobLock) {
    this.jobLock = jobLock;
  }

  private void exclusively(String name, Runnable job) {
    if (jobLock == null) job.run();
    else jobLock.runExclusively(name, job);
  }

  @Scheduled(
      cron = "${app.skatteverket.tax-table-import.cron:0 15 3 * * *}",
      zone = "Europe/Stockholm")
  public void importPublishedYearIfMissing() {
    exclusively("skatteverket-tax-import", () -> {
      LocalDate today = LocalDate.now(STOCKHOLM);
      importYearIfMissing(today.getYear());
      if (today.getMonthValue() == 12) {
        importYearIfMissing(today.getYear() + 1);
      }
    });
  }

  @EventListener(ApplicationReadyEvent.class)
  public void importAfterStartup() {
    importPublishedYearIfMissing();
  }

  private void importYearIfMissing(int requestedYear) {
    importMonthlyIfMissing(requestedYear);
    importOneTimeIfMissing(requestedYear);
  }

  private void importMonthlyIfMissing(int requestedYear) {
    if (store.hasCompleteYear(requestedYear)) return;
    log.info("Checking Skatteverket monthly tax tables for {}", requestedYear);
    try {
      URI fileUri = discoverMonthlyTableUri(requestedYear, download(indexUri));
      List<TaxTableImportRow> rows = parseMonthlyTable(requestedYear, download(fileUri));
      validate(rows);
      store.replaceYear(requestedYear, rows);
      log.info("Imported {} Skatteverket monthly tax-table rows for {}", rows.size(), requestedYear);
    } catch (TaxTableNotPublishedException exception) {
      log.info("Skatteverket monthly tax tables for {} are not published yet", requestedYear);
    } catch (Exception exception) {
      log.error("Could not import Skatteverket monthly tax tables for {} ({})",
          requestedYear, exception.getClass().getSimpleName());
    }
  }

  private void importOneTimeIfMissing(int requestedYear) {
    if (store.hasCompleteOneTimeYear(requestedYear)) return;
    log.info("Checking Skatteverket one-time tax tables for {}", requestedYear);
    try {
      List<OneTimeTaxImportRow> rows = parseOneTimeTables(requestedYear, download(oneTimeTableUri));
      validateOneTimeRows(rows);
      store.replaceOneTimeYear(requestedYear, rows);
      log.info("Imported {} Skatteverket one-time tax brackets for {}", rows.size(), requestedYear);
    } catch (TaxTableNotPublishedException exception) {
      log.info("Skatteverket one-time tax tables for {} are not published yet", requestedYear);
    } catch (Exception exception) {
      log.error("Could not import Skatteverket one-time tax tables for {} ({})",
          requestedYear, exception.getClass().getSimpleName());
    }
  }

  private String download(URI uri) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "text/html,text/plain")
        .header("User-Agent", "NordicFramtiden payroll tax-table importer")
        .GET()
        .build();
    HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("Skatteverket returned HTTP " + response.statusCode());
    }
    return new String(response.body(), StandardCharsets.UTF_8);
  }

  static URI discoverMonthlyTableUri(int year, String html) {
    String heading = "Samtliga tabeller " + year;
    int sectionStart = html.indexOf(heading);
    if (sectionStart < 0) {
      throw new TaxTableNotPublishedException();
    }
    int sectionEnd = html.indexOf("<h2", sectionStart + heading.length());
    String section = html.substring(sectionStart, sectionEnd < 0 ? html.length() : sectionEnd);
    Matcher matcher = DOWNLOAD_LINK.matcher(section);
    if (!matcher.find()) {
      throw new TaxTableNotPublishedException();
    }
    return URI.create("https://www.skatteverket.se").resolve(matcher.group(1).replace("&amp;", "&"));
  }

  static List<TaxTableImportRow> parseMonthlyTable(int year, String text) {
    List<TaxTableImportRow> rows = new ArrayList<>();
    for (String rawLine : text.replace("\uFEFF", "").lines().toList()) {
      if (rawLine.isBlank() || rawLine.length() < 20 || !rawLine.startsWith("30")) {
        continue;
      }
      try {
        boolean percentage = rawLine.charAt(2) == '%';
        if (!percentage && rawLine.charAt(2) != 'B') {
          continue;
        }
        int tableNumber = Integer.parseInt(rawLine.substring(3, 5));
        int incomeFrom = Integer.parseInt(rawLine.substring(5, 12).trim());
        String incomeToText = rawLine.substring(12, 19).trim();
        int incomeTo = incomeToText.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(incomeToText);
        // The six tax columns are five-character right-aligned fields. Values of
        // 10 000 kr and above fill their field completely, so the columns cannot
        // be recovered by splitting on whitespace; they must be read positionally.
        String line = padToColumnArea(rawLine);
        int[] columns = new int[6];
        for (int column = 0; column < columns.length; column++) {
          int fieldStart = COLUMN_FIELD_START + COLUMN_FIELD_WIDTH * column;
          String field = line.substring(fieldStart, fieldStart + COLUMN_FIELD_WIDTH).trim();
          if (field.isEmpty()) {
            throw new IllegalArgumentException("tax column " + (column + 1) + " is empty");
          }
          columns[column] = Integer.parseInt(field);
        }
        rows.add(new TaxTableImportRow(year, tableNumber, incomeFrom, incomeTo,
            columns[0], columns[1], columns[2], columns[3], columns[4], columns[5], percentage));
      } catch (RuntimeException exception) {
        throw new IllegalArgumentException("Invalid Skatteverket tax-table row", exception);
      }
    }
    return rows;
  }

  static List<OneTimeTaxImportRow> parseOneTimeTables(int year, String html) {
    List<OneTimeTaxImportRow> rows = new ArrayList<>();
    Pattern tablePattern = Pattern.compile("(?is)id=\"Engangstabellkolumn([1-6])" + year + "\".*?<tbody>(.*?)</tbody>");
    Matcher tables = tablePattern.matcher(html);
    while (tables.find()) {
      int column = Integer.parseInt(tables.group(1));
      Matcher tableRows = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(tables.group(2));
      while (tableRows.find()) {
        Matcher cells = Pattern.compile("(?is)<td[^>]*>(.*?)</td>").matcher(tableRows.group(1));
        List<String> values = new ArrayList<>();
        while (cells.find()) values.add(cleanHtmlNumber(cells.group(1)));
        if (values.size() != 4) throw new IllegalArgumentException("Invalid one-time tax row for column " + column);
        int from = Integer.parseInt(values.get(0));
        int to = values.get(2).isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(values.get(2));
        int percent = Integer.parseInt(values.get(3));
        rows.add(new OneTimeTaxImportRow(year, column, from, to, percent));
      }
    }
    if (rows.isEmpty()) throw new TaxTableNotPublishedException();
    return rows;
  }

  private static String cleanHtmlNumber(String value) {
    return value.replaceAll("(?is)<[^>]+>", "").replace("&nbsp;", "")
        .replace(" ", "").replace(" ", "").replace("%", "").trim();
  }

  private static void validateOneTimeRows(List<OneTimeTaxImportRow> rows) {
    Set<Integer> columns = new HashSet<>();
    rows.forEach(row -> columns.add(row.taxColumn()));
    if (rows.size() < 24 || !columns.equals(Set.of(1, 2, 3, 4, 5, 6)))
      throw new IllegalArgumentException("Incomplete one-time tax tables: " + rows.size() + " rows and columns " + columns);
    for (int column : columns) {
      if (rows.stream().noneMatch(row -> row.taxColumn() == column && row.annualIncomeTo() == Integer.MAX_VALUE))
        throw new IllegalArgumentException("One-time tax column " + column + " has no open-ended bracket");
    }
  }

  private static String padToColumnArea(String rawLine) {
    int requiredLength = COLUMN_FIELD_START + COLUMN_FIELD_WIDTH * 6;
    return rawLine.length() >= requiredLength ? rawLine : String.format("%-" + requiredLength + "s", rawLine);
  }

  static void validate(List<TaxTableImportRow> rows) {
    // Monthly tables are expressed in kronor up to 80 000 kr; above that every
    // row is a percentage row. A file that violates this is misparsed and must
    // never replace the stored tables, or raw percentages would be paid out as
    // kronor.
    for (TaxTableImportRow row : rows) {
      if (!row.percentage() && row.incomeFrom() > TaxTableRow.MONTHLY_KRONOR_INCOME_LIMIT) {
        throw new IllegalArgumentException(
            "Kronor row above " + TaxTableRow.MONTHLY_KRONOR_INCOME_LIMIT + " kr: " + row);
      }
    }
    // Each table must end in exactly one open-ended percentage row.
    Set<Integer> tableNumbers = new HashSet<>();
    rows.forEach(row -> tableNumbers.add(row.tableNumber()));
    for (int tableNumber : tableNumbers) {
      List<TaxTableImportRow> openEnded = rows.stream()
          .filter(row -> row.tableNumber() == tableNumber && row.incomeTo() == Integer.MAX_VALUE)
          .toList();
      if (openEnded.size() != 1 || !openEnded.get(0).percentage()) {
        throw new IllegalArgumentException("Table " + tableNumber
            + " must have exactly one open-ended percentage row, found " + openEnded.size());
      }
    }
    if (rows.size() < MINIMUM_ROW_COUNT || tableNumbers.size() != EXPECTED_TABLE_COUNT
        || !tableNumbers.containsAll(Set.of(29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42))) {
      throw new IllegalArgumentException(
          "Incomplete Skatteverket file: " + rows.size() + " rows and tables " + tableNumbers);
    }
  }

  record TaxTableImportRow(int taxYear, int tableNumber, int incomeFrom, int incomeTo,
      int col1, int col2, int col3, int col4, int col5, int col6, boolean percentage) {}
  record OneTimeTaxImportRow(int taxYear, int taxColumn, int annualIncomeFrom, int annualIncomeTo, int taxPercent) {}

  private static final class TaxTableNotPublishedException extends RuntimeException {}
}
