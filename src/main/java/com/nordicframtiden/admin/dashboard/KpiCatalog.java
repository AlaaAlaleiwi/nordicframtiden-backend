package com.nordicframtiden.admin.dashboard;

import java.util.List;
import java.util.Map;

/**
 * Aggregate-only KPI catalog for the admin dashboard customization.
 *
 * GDPR guardrails: every entry is a count, sum, average or ratio across the
 * full dataset. No field here may ever reference a person, contact detail,
 * salary figure or document content of an individual user — those stay in the
 * existing detail views behind admin auth.
 */
public final class KpiCatalog {

    private KpiCatalog() {
    }

    public enum Unit {
        COUNT("count"),
        HOURS("hours"),
        SEK("SEK"),
        PERCENT("percent"),
        MINUTES("minutes");

        private final String wireName;

        Unit(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /**
     * One offered KPI: identity + presentation hints. The backend is the
     * source of truth so every client (iOS, web, Android) renders the same
     * set without hardcoding keys.
     */
    public record KpiDefinition(
            String key,
            String category,
            String titleKey,
            String descriptionKey,
            String unit,
            boolean supportsTrend,
            List<String> visualizations,
            String icon,
            String defaultColor) {
    }

    /** Value of one KPI for the requested period. */
    public record KpiValue(
            double value,
            Double previousValue,
            String unit) {
    }

    public record DashboardKpiResponse(
            String period,
            String periodLabel,
            Map<String, KpiValue> kpis) {
    }

    public static final List<KpiDefinition> ALL = List.of(
            // People (GDPR-safe: counts and ratios only)
            def("people.totalUsers", "people", "kpi.people.totalUsers", "kpi.people.totalUsers.hint",
                    Unit.COUNT, true, "person.2.fill", "blue"),
            def("people.totalAdmins", "people", "kpi.people.totalAdmins", "kpi.people.totalAdmins.hint",
                    Unit.COUNT, false, "shield.fill", "purple"),
            def("people.totalStaff", "people", "kpi.people.totalStaff", "kpi.people.totalStaff.hint",
                    Unit.COUNT, false, "briefcase.fill", "orange"),
            def("people.activeRatio", "people", "kpi.people.activeRatio", "kpi.people.activeRatio.hint",
                    Unit.PERCENT, false, "person.crop.circle.badge.checkmark", "green"),

            // Pharmacies
            def("pharmacies.total", "pharmacies", "kpi.pharmacies.total", "kpi.pharmacies.total.hint",
                    Unit.COUNT, false, "building.2.fill", "green"),
            def("pharmacies.active", "pharmacies", "kpi.pharmacies.active", "kpi.pharmacies.active.hint",
                    Unit.COUNT, false, "building.2", "teal"),
            def("pharmacies.avgHourlyCost", "pharmacies", "kpi.pharmacies.avgHourlyCost", "kpi.pharmacies.avgHourlyCost.hint",
                    Unit.SEK, false, "banknote", "teal"),

            // Schedule (ScheduleShift.user is non-nullable in this schema, so
            // an "assignment rate" cannot be derived server-side; clients
            // that receive optional-user events may compute it locally.)
            def("schedule.totalShifts", "schedule", "kpi.schedule.totalShifts", "kpi.schedule.totalShifts.hint",
                    Unit.COUNT, true, "calendar", "blue"),
            def("schedule.scheduledHours", "schedule", "kpi.schedule.scheduledHours", "kpi.schedule.scheduledHours.hint",
                    Unit.HOURS, true, "clock", "blue"),
            def("schedule.scheduledPharmacists", "schedule", "kpi.schedule.scheduledPharmacists", "kpi.schedule.scheduledPharmacists.hint",
                    Unit.COUNT, false, "person.2", "purple"),
            def("schedule.uniquePharmacies", "schedule", "kpi.schedule.uniquePharmacies", "kpi.schedule.uniquePharmacies.hint",
                    Unit.COUNT, false, "building.2", "indigo"),

            // Salary — aggregates only, never an individual figure
            def("salary.totalMonthlyCost", "salary", "kpi.salary.totalMonthlyCost", "kpi.salary.totalMonthlyCost.hint",
                    Unit.SEK, true, "banknote", "teal"),
            def("salary.totalMonthlyHours", "salary", "kpi.salary.totalMonthlyHours", "kpi.salary.totalMonthlyHours.hint",
                    Unit.HOURS, true, "calendar", "blue"),
            def("salary.avgCostPerHour", "salary", "kpi.salary.avgCostPerHour", "kpi.salary.avgCostPerHour.hint",
                    Unit.SEK, false, "chart.bar", "teal"),

            // Contacts
            def("contacts.unhandled", "contacts", "kpi.contacts.unhandled", "kpi.contacts.unhandled.hint",
                    Unit.COUNT, false, "envelope.fill", "red"),
            def("contacts.total", "contacts", "kpi.contacts.total", "kpi.contacts.total.hint",
                    Unit.COUNT, true, "envelope", "blue"),
            def("contacts.handledRatio", "contacts", "kpi.contacts.handledRatio", "kpi.contacts.handledRatio.hint",
                    Unit.PERCENT, false, "checkmark.seal.fill", "green"),

            // Availability
            def("availability.pending", "availability", "kpi.availability.pending", "kpi.availability.pending.hint",
                    Unit.COUNT, false, "checkmark.seal", "orange"),

            // Calls
            def("calls.total", "calls", "kpi.calls.total", "kpi.calls.total.hint",
                    Unit.COUNT, false, "phone.fill", "blue"),
            def("calls.completed", "calls", "kpi.calls.completed", "kpi.calls.completed.hint",
                    Unit.COUNT, false, "phone.badge.checkmark", "green"),
            def("calls.missed", "calls", "kpi.calls.missed", "kpi.calls.missed.hint",
                    Unit.COUNT, false, "phone.badge.exclamationmark", "red"),
            def("calls.totalMinutes", "calls", "kpi.calls.totalMinutes", "kpi.calls.totalMinutes.hint",
                    Unit.MINUTES, false, "clock", "orange"),
            def("calls.videoCount", "calls", "kpi.calls.videoCount", "kpi.calls.videoCount.hint",
                    Unit.COUNT, false, "video.fill", "purple"),

            // Documents (ProfileDocument has no verification flag in this
            // schema, so only the total count is offered.)
            def("documents.total", "documents", "kpi.documents.total", "kpi.documents.total.hint",
                    Unit.COUNT, false, "doc.fill", "blue"),

            // GDPR (counts only, never individual requests)
            def("gdpr.openDeletionRequests", "gdpr", "kpi.gdpr.openDeletionRequests", "kpi.gdpr.openDeletionRequests.hint",
                    Unit.COUNT, false, "trash.fill", "red"));

    private static KpiDefinition def(
            String key, String category, String titleKey, String descriptionKey,
            Unit unit, boolean supportsTrend, String icon, String defaultColor) {
        List<String> visualizations = supportsTrend
                ? List.of("number", "trend", "bar")
                : List.of("number", "bar");
        boolean isRatio = unit == Unit.PERCENT;
        if (isRatio) {
            visualizations = List.of("number", "ring", "bar");
        }
        return new KpiDefinition(key, category, titleKey, descriptionKey,
                unit.wireName(), supportsTrend, visualizations, icon, defaultColor);
    }

    public static List<KpiDefinition> catalog() {
        return ALL;
    }

    public static boolean isKnownKey(String key) {
        return ALL.stream().anyMatch(d -> d.key().equals(key));
    }
}
