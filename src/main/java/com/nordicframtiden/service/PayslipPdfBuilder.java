package com.nordicframtiden.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import com.lowagie.text.pdf.PdfPCell;

import java.util.List;

/**
 * Builds the automatic payslip email attachment: an A4 "Lönespecifikation"
 * mirroring the apps' own PDFs (summary block + day table). Pure function of
 * the payslip data — no repository or mail access — so it is unit-testable
 * without Spring. OpenPDF is already on the classpath (used by other
 * documents), so no new dependency is introduced.
 */
@Component
public class PayslipPdfBuilder {

    private static final Font TITLE = new Font(Font.HELVETICA, 18, Font.BOLD);
    private static final Font HEADING = new Font(Font.HELVETICA, 12, Font.BOLD);
    private static final Font BODY = new Font(Font.HELVETICA, 10);
    private static final Font SMALL = new Font(Font.HELVETICA, 8);
    private static final Font TABLE_HEADER = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);
    private static final Color HEADER_BG = new Color(15, 81, 50);
    private static final Color ROW_BG = new Color(244, 246, 248);

    /** One rendered row in the day table. */
    public record DayLine(String day, String from, String to, String hours, BigDecimal cost) {
    }

    /**
     * Renders the payslip PDF. Pure and side-effect free beyond the byte
     * array it returns.
     */
    public byte[] build(String employeeName, int year, int month, List<DayLine> days,
                        BigDecimal gross, BigDecimal tax, BigDecimal net, BigDecimal totalHours) {
        return build(employeeName, year, month, days, gross, tax, null, net, totalHours, null);
    }

    /**
     * Renders the payslip PDF including the tax-free reimbursement line
     * (shown when non-zero, so gross − tax + tax-free = net adds up) and the
     * finalized revision number (shown when {@code revision} is non-null).
     */
    public byte[] build(String employeeName, int year, int month, List<DayLine> days,
                        BigDecimal gross, BigDecimal tax, BigDecimal taxFree, BigDecimal net,
                        BigDecimal totalHours, Integer revision) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 40, 40, 40, 40);
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(new Paragraph("Lönespecifikation", TITLE));
            document.add(spacer(8));
            document.add(new Paragraph("Namn: " + safe(employeeName), BODY));
            document.add(new Paragraph("Period: " + monthLabel(year, month), BODY));
            if (revision != null) {
                document.add(new Paragraph("Version: " + revision, BODY));
            }
            document.add(spacer(10));

            document.add(new Paragraph("Sammanfattning", HEADING));
            PdfPTable summary = table(2);
            summary.setWidths(new float[]{3f, 2f});
            row(summary, "Totala timmar", hours(totalHours) + " h");
            row(summary, "Bruttolön", money(gross));
            row(summary, "Skatt", "-" + money(tax));
            if (taxFree != null && taxFree.signum() != 0) {
                row(summary, "Skattefri ersättning", money(taxFree));
            }
            boldRow(summary, "Nettolön", money(net));
            document.add(summary);
            document.add(spacer(12));

            document.add(new Paragraph("Arbetade dagar", HEADING));
            PdfPTable daysTable = table(5);
            daysTable.setWidths(new float[]{1.2f, 1.6f, 1.6f, 1.2f, 1.6f});
            headerRow(daysTable, "Dag", "Från", "Till", "Timmar", "Kostnad");
            if (days.isEmpty()) {
                cells(daysTable, "-", "-", "-", "-", "-");
            }
            for (DayLine day : days) {
                cells(daysTable, day.day(), day.from(), day.to(), day.hours(), money(day.cost()));
            }
            document.add(daysTable);

            document.add(spacer(14));
            document.add(new Paragraph(
                "Automatiskt utskick från Nordic Framtiden — genererad "
                    + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                SMALL));
            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build payslip PDF", e);
        }
    }

    private void row(PdfPTable table, String key, String value) {
        cells(table, key, value);
    }

    private void boldRow(PdfPTable table, String key, String value) {
        var keyCell = new PdfPCell(new com.lowagie.text.Phrase(key, HEADING));
        keyCell.setBackgroundColor(ROW_BG);
        keyCell.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
        var valueCell = new PdfPCell(new com.lowagie.text.Phrase(value, HEADING));
        valueCell.setBackgroundColor(ROW_BG);
        valueCell.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
        valueCell.setHorizontalAlignment(com.lowagie.text.Element.ALIGN_RIGHT);
        table.addCell(keyCell);
        table.addCell(valueCell);
    }

    private void headerRow(PdfPTable table, String... labels) {
        for (String label : labels) {
            var cell = new PdfPCell(new com.lowagie.text.Phrase(label, TABLE_HEADER));
            cell.setBackgroundColor(HEADER_BG);
            cell.setHorizontalAlignment(com.lowagie.text.Element.ALIGN_CENTER);
            cell.setPadding(5);
            table.addCell(cell);
        }
    }

    private void cells(PdfPTable table, String... values) {
        for (String value : values) {
            var cell = new PdfPCell(new com.lowagie.text.Phrase(safe(value), BODY));
            cell.setBorder(com.lowagie.text.Rectangle.BOTTOM);
            cell.setPadding(4);
            table.addCell(cell);
        }
    }

    private PdfPTable table(int columns) {
        PdfPTable table = new PdfPTable(columns);
        table.setWidthPercentage(100);
        table.setSpacingBefore(6);
        return table;
    }

    private Paragraph spacer(int points) {
        Paragraph paragraph = new Paragraph(" ");
        paragraph.setLeading(points);
        return paragraph;
    }

    private String monthLabel(int year, int month) {
        return "%04d-%02d".formatted(year, month);
    }

    private String hours(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
            .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " SEK";
    }

    private String safe(String value) {
        return value == null ? "-" : value;
    }
}
