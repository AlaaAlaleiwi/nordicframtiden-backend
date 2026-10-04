package com.nordicframtiden.admin.dashboard;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the KPI catalog and period windows. The GDPR rule is
 * enforced structurally here: every catalog key must belong to a whitelist
 * of aggregate metric families, and no key may encode a per-person dimension.
 */
class KpiCatalogTest {

    /** Aggregate metric families allowed on the dashboard. */
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "people.", "pharmacies.", "schedule.", "salary.", "contacts.",
            "availability.", "calls.", "documents.", "gdpr.");

    /** Key fragments that would leak per-person data if anyone ever adds them. */
    private static final Set<String> FORBIDDEN_SNIPPETS = Set.of(
            "name", "email", "phone", "birth", "salaryof", "userdetail",
            "personal", "address", "consentof");

    @Test
    void everyKpiIsAggregateAndCategorized() {
        assertTrue(KpiCatalog.ALL.size() >= 20, "the catalog should offer the full KPI set");

        for (KpiCatalog.KpiDefinition def : KpiCatalog.ALL) {
            assertTrue(
                    ALLOWED_PREFIXES.stream().anyMatch(def.key()::startsWith),
                    def.key() + " must belong to a whitelisted aggregate family");
            for (String forbidden : FORBIDDEN_SNIPPETS) {
                assertFalse(def.key().toLowerCase(Locale.ROOT).contains(forbidden),
                        def.key() + " must never encode per-person data");
            }
            assertTrue(def.visualizations().contains("number"), def.key() + " must render as a number");
            assertFalse(def.titleKey().isBlank());
            assertFalse(def.unit().isBlank());
        }
    }

    @Test
    void catalogKeysAreUnique() {
        var keys = KpiCatalog.ALL.stream().map(KpiCatalog.KpiDefinition::key).collect(Collectors.toSet());
        assertEquals(KpiCatalog.ALL.size(), keys.size(), "duplicate KPI keys would silently overwrite each other");
    }

    @Test
    void salaryKpisAreAggregateOnly() {
        var salaryKeys = KpiCatalog.ALL.stream()
                .filter(d -> d.category().equals("salary"))
                .map(KpiCatalog.KpiDefinition::key)
                .collect(Collectors.toSet());
        // Every salary KPI is a sum/average over the whole dataset — no
        // per-person figure may appear.
        assertEquals(Set.of("salary.totalMonthlyCost", "salary.totalMonthlyHours", "salary.avgCostPerHour"),
                salaryKeys);
    }

    @Test
    void periodWindowsAreCorrect() {
        // A Wednesday: week starts Monday Sep 28 and ends (inclusive) Oct 4.
        LocalDate wednesday = LocalDate.of(2026, 9, 30);

        var week = AdminDashboardKpiService.Period.WEEK;
        assertEquals(LocalDate.of(2026, 9, 28), week.start(wednesday));
        assertEquals(LocalDate.of(2026, 10, 4), week.end(wednesday).minusDays(1));
        assertEquals(LocalDate.of(2026, 9, 21), week.previousStart(wednesday));

        var month = AdminDashboardKpiService.Period.MONTH;
        assertEquals(LocalDate.of(2026, 9, 1), month.start(wednesday));
        assertEquals(LocalDate.of(2026, 9, 30), month.end(wednesday).minusDays(1));
        assertEquals(LocalDate.of(2026, 8, 1), month.previousStart(wednesday));

        var quarter = AdminDashboardKpiService.Period.QUARTER;
        assertEquals(LocalDate.of(2026, 7, 1), quarter.start(wednesday));
        assertEquals(LocalDate.of(2026, 9, 30), quarter.end(wednesday).minusDays(1));

        // Labels are inclusive ranges.
        assertEquals("Sep 28 – Oct 4",
                week.label(wednesday, DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)));
    }

    @Test
    void unknownPeriodFallsBackToWeek() {
        assertEquals(AdminDashboardKpiService.Period.WEEK, AdminDashboardKpiService.Period.from("bogus"));
        assertEquals(AdminDashboardKpiService.Period.WEEK, AdminDashboardKpiService.Period.from(null));
        assertEquals(AdminDashboardKpiService.Period.WEEK, AdminDashboardKpiService.Period.from("  "));
        assertEquals(AdminDashboardKpiService.Period.MONTH, AdminDashboardKpiService.Period.from("month"));
        assertEquals(AdminDashboardKpiService.Period.QUARTER, AdminDashboardKpiService.Period.from("QUARTER"));
    }
}
