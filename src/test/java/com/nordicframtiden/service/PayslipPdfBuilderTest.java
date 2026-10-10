package com.nordicframtiden.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The automatic payslip email attachment must be a structurally valid PDF
 * (header marker + EOF marker + page tree) for any input, including empty
 * day lists and null totals.
 */
class PayslipPdfBuilderTest {

    private final PayslipPdfBuilder builder = new PayslipPdfBuilder();

    @Test
    void build_producesAValidPdfDocument() {
        byte[] pdf = builder.build("Anna Andersson", 2026, 8,
            List.of(new PayslipPdfBuilder.DayLine("2026-08-03", "08:00", "17:00", "8.00", new BigDecimal("1600"))),
            new BigDecimal("24000"), new BigDecimal("4800"), new BigDecimal("19200"), new BigDecimal("120"));

        String content = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(content).startsWith("%PDF-");
        assertThat(content).contains("%%EOF");
        assertThat(pdf.length).isGreaterThan(500);
    }

    @Test
    void build_toleratesEmptyDaysAndNullTotals() {
        byte[] pdf = builder.build("", 2026, 8, List.of(), null, null, null, null);

        String content = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(content).startsWith("%PDF-");
        assertThat(content).contains("%%EOF");
    }

    @Test
    void build_multiPageDayListsStayValid() {
        List<PayslipPdfBuilder.DayLine> days = new java.util.ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            days.add(new PayslipPdfBuilder.DayLine("2026-08-%02d".formatted(i % 28 + 1),
                "08:00", "17:00", "8.00", BigDecimal.TEN));
        }
        byte[] pdf = builder.build("Anna", 2026, 8, days,
            new BigDecimal("24000"), new BigDecimal("4800"), new BigDecimal("19200"), new BigDecimal("320"));

        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1)).contains("%%EOF");
    }

    @Test
    void build_showsTaxFreeReimbursementAndRevisionSoTotalsAddUp() throws Exception {
        byte[] pdf = builder.build("Anna", 2026, 8, List.of(),
            new BigDecimal("24000"), new BigDecimal("4800"), new BigDecimal("500"),
            new BigDecimal("19700"), new BigDecimal("120"), 3);

        String text = text(pdf);
        assertThat(text).contains("Skattefri ersättning").contains("500.00 SEK");
        assertThat(text).contains("Version: 3");
        assertThat(text).contains("19700.00 SEK");
    }

    @Test
    void build_omitsZeroTaxFreeLineAndRevisionForDrafts() throws Exception {
        byte[] pdf = builder.build("Anna", 2026, 8, List.of(),
            new BigDecimal("24000"), new BigDecimal("4800"), BigDecimal.ZERO,
            new BigDecimal("19200"), new BigDecimal("120"), null);

        String text = text(pdf);
        assertThat(text).doesNotContain("Skattefri").doesNotContain("Version");
        assertThat(text).contains("19200.00 SEK");
    }

    private static String text(byte[] pdf) throws Exception {
        try (var reader = new com.lowagie.text.pdf.PdfReader(pdf)) {
            var extractor = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            return text.toString();
        }
    }
}
