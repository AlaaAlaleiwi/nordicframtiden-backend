package com.nordicframtiden.admin.dashboard;

import com.nordicframtiden.availability.AvailabilityRequest;
import com.nordicframtiden.availability.AvailabilityRequestRepository;
import com.nordicframtiden.chat.CallHistory;
import com.nordicframtiden.chat.CallHistoryRepository;
import com.nordicframtiden.contact.ContactRequestRepository;
import com.nordicframtiden.documents.ProfileDocumentRepository;
import com.nordicframtiden.gdpr.GdprDeletionRequest;
import com.nordicframtiden.gdpr.GdprDeletionRequestRepository;
import com.nordicframtiden.pharmacy.Pharmacy;
import com.nordicframtiden.pharmacy.PharmacyRepository;
import com.nordicframtiden.pharmacy.ScheduleShift;
import com.nordicframtiden.pharmacy.ScheduleShiftRepository;
import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.repo.AppUserRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Computes every catalog KPI as an aggregate over the requested period.
 *
 * GDPR: only counts/sums/averages/rates are produced here — the service never
 * places a person, a contact detail or an individual salary figure in the
 * response, and it persists nothing. Individual-level data remains in the
 * existing detail views behind admin auth.
 */
@Service
public class AdminDashboardKpiService {

    private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");

    private final AppUserRepository users;
    private final PharmacyRepository pharmacies;
    private final ScheduleShiftRepository shifts;
    private final ContactRequestRepository contacts;
    private final AvailabilityRequestRepository availability;
    private final CallHistoryRepository calls;
    private final ProfileDocumentRepository documents;
    private final GdprDeletionRequestRepository deletionRequests;

    public AdminDashboardKpiService(
            AppUserRepository users,
            PharmacyRepository pharmacies,
            ScheduleShiftRepository shifts,
            ContactRequestRepository contacts,
            AvailabilityRequestRepository availability,
            CallHistoryRepository calls,
            ProfileDocumentRepository documents,
            GdprDeletionRequestRepository deletionRequests) {
        this.users = users;
        this.pharmacies = pharmacies;
        this.shifts = shifts;
        this.contacts = contacts;
        this.availability = availability;
        this.calls = calls;
        this.documents = documents;
        this.deletionRequests = deletionRequests;
    }

    public KpiCatalog.DashboardKpiResponse compute(String periodParam) {
        Period period = Period.from(periodParam);
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = period.start(today);
        LocalDate end = period.end(today);            // exclusive
        LocalDate prevStart = period.previousStart(today);

        Map<String, KpiCatalog.KpiValue> kpis = new LinkedHashMap<>();
        RequestData data = new RequestData();
        for (KpiCatalog.KpiDefinition def : KpiCatalog.ALL) {
            Double current = valueFor(def.key(), start, end, data);
            Double previous = def.supportsTrend()
                    ? valueFor(def.key(), prevStart, start, data)   // previous window: [prevStart, start)
                    : null;
            kpis.put(def.key(), new KpiCatalog.KpiValue(
                    current != null ? current : 0.0,
                    previous,
                    def.unit()));
        }
        return new KpiCatalog.DashboardKpiResponse(period.name(), periodLabel(period, today), kpis);
    }

    private String periodLabel(Period period, LocalDate today) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
        return period.label(today, formatter);
    }

    /** Null means "not computable from this schema" — reported as 0 with no trend. */
    private Double valueFor(String key, LocalDate start, LocalDate end, RequestData data) {
        return switch (key) {
            // People — counts and ratios only
            case "people.totalUsers" -> (double) data.count("users", users::count);
            case "people.totalAdmins" -> (double) users.countByRole(Role.ADMIN);
            case "people.totalStaff" -> (double) users.countByRole(Role.STAFF);
            case "people.activeRatio" -> ratio(users.countByEnabledTrue(), data.count("users", users::count));

            // Pharmacies
            case "pharmacies.total" -> (double) pharmacies.count();
            case "pharmacies.active" -> (double) pharmacies.countByEnabledTrue();
            case "pharmacies.avgHourlyCost" -> avgPharmacyHourlyCost();

            // Schedule
            case "schedule.totalShifts" -> (double) data.shiftsInWindow(start, end).size();
            case "schedule.scheduledHours" -> hoursOf(data.shiftsInWindow(start, end));
            case "schedule.scheduledPharmacists" -> (double) data.shiftsInWindow(start, end).stream()
                    .map(s -> s.getUser().getId()).distinct().count();
            case "schedule.uniquePharmacies" -> (double) data.shiftsInWindow(start, end).stream()
                    .map(s -> s.getPharmacy().getId()).distinct().count();

            // Salary — aggregate of the per-shift cost snapshots (never per person)
            case "salary.totalMonthlyCost" -> totalCost(data.shiftsInWindow(start, end));
            case "salary.totalMonthlyHours" -> hoursOf(data.shiftsInWindow(start, end));
            case "salary.avgCostPerHour" -> avgCostPerHour(data.shiftsInWindow(start, end));

            // Contacts
            case "contacts.unhandled" -> (double) data.count("unhandledContacts", contacts::countByHandledFalse);
            case "contacts.total" -> (double) data.count("contacts", contacts::count);
            case "contacts.handledRatio" -> ratio(data.count("contacts", contacts::count)
                    - data.count("unhandledContacts", contacts::countByHandledFalse), data.count("contacts", contacts::count));

            // Availability
            case "availability.pending" -> (double) availability.countByStatus(AvailabilityRequest.Status.PENDING);

            // Calls
            case "calls.total", "calls.completed", "calls.missed", "calls.totalMinutes", "calls.videoCount" ->
                    callMetric(key, start, end, data);

            // Documents
            case "documents.total" -> (double) documents.count();

            // GDPR — the open-request count only, never the requests
            case "gdpr.openDeletionRequests" ->
                    (double) deletionRequests.countByStatus(GdprDeletionRequest.STATUS_PENDING);

            default -> 0.0;
        };
    }

    private record Window(LocalDate start, LocalDate end) {}

    /** Reuse data only within this response; never retain one user's request
     * across requests or return stale aggregates after a database update. */
    private final class RequestData {
        private final Map<Window, List<ScheduleShift>> scheduleWindows = new HashMap<>();
        private final Map<Window, List<CallHistory>> callWindows = new HashMap<>();
        private final Map<String, Long> counts = new HashMap<>();

        List<ScheduleShift> shiftsInWindow(LocalDate start, LocalDate end) {
            return scheduleWindows.computeIfAbsent(new Window(start, end),
                    window -> AdminDashboardKpiService.this.shiftsInWindow(window.start(), window.end()));
        }

        List<CallHistory> callsInWindow(LocalDate start, LocalDate end) {
            return callWindows.computeIfAbsent(new Window(start, end), window -> calls.findByStartedAtBetween(
                    window.start().atStartOfDay(ZONE).toInstant(), window.end().atStartOfDay(ZONE).toInstant()));
        }

        long count(String key, Supplier<Long> query) {
            return counts.computeIfAbsent(key, ignored -> query.get());
        }
    }

    private java.util.List<ScheduleShift> shiftsInWindow(LocalDate start, LocalDate end) {
        return shifts.findInRange(
                start.atStartOfDay(ZONE).toOffsetDateTime(),
                end.atStartOfDay(ZONE).toOffsetDateTime(),
                null, null);
    }

    private static double hoursOf(java.util.List<ScheduleShift> windowShifts) {
        return windowShifts.stream()
                .mapToDouble(s -> Duration.between(s.getStartAt(), s.getEndAt()).toMinutes() / 60.0)
                .filter(h -> h > 0)
                .sum();
    }

    private static Double totalCost(java.util.List<ScheduleShift> windowShifts) {
        BigDecimal sum = windowShifts.stream()
                .map(ScheduleShift::getHourlyCostSnapshot)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.doubleValue();
    }

    private static Double avgCostPerHour(java.util.List<ScheduleShift> windowShifts) {
        double hours = hoursOf(windowShifts);
        if (hours <= 0) {
            return null;
        }
        Double cost = totalCost(windowShifts);
        if (cost == null || cost <= 0) {
            return null;
        }
        return cost / hours;
    }

    private Double avgPharmacyHourlyCost() {
        var withCost = pharmacies.findAll().stream()
                .filter(p -> p.isEnabled() && p.getHourlyCost() != null)
                .toList();
        if (withCost.isEmpty()) {
            return null;
        }
        BigDecimal sum = withCost.stream()
                .map(Pharmacy::getHourlyCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(withCost.size()), 2, RoundingMode.HALF_UP).doubleValue();
    }

    private static double ratio(long part, long total) {
        return total > 0 ? (double) part / total : 0;
    }

    private double callMetric(String key, LocalDate start, LocalDate end, RequestData data) {
        var window = data.callsInWindow(start, end);
        long completed = window.stream().filter(c -> "COMPLETED".equals(c.getOutcome())).count();
        return switch (key) {
            case "calls.completed" -> (double) completed;
            case "calls.missed" -> window.size() - completed;
            // CallHistory has no stored duration; derive from ended/started,
            // falling back to answered/started for calls still open.
            case "calls.totalMinutes" -> window.stream()
                    .mapToLong(this::callMinutes)
                    .sum();
            case "calls.videoCount" -> window.stream().filter(CallHistory::isVideo).count();
            default -> (double) window.size();
        };
    }

    private long callMinutes(CallHistory call) {
        var end = call.getEndedAt() != null ? call.getEndedAt() : call.getAnsweredAt();
        if (end == null) {
            return 0;
        }
        return Math.max(0, ChronoUnit.SECONDS.between(call.getStartedAt(), end) / 60);
    }

    enum Period {
        WEEK, MONTH, QUARTER;

        static Period from(String raw) {
            if (raw == null || raw.isBlank()) {
                return WEEK;
            }
            try {
                return Period.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return WEEK;
            }
        }

        LocalDate start(LocalDate today) {
            return switch (this) {
                case WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                case MONTH -> today.withDayOfMonth(1);
                case QUARTER -> today.withDayOfMonth(1)
                        .withMonth(((today.getMonthValue() - 1) / 3) * 3 + 1);
            };
        }

        LocalDate end(LocalDate today) {
            // Exclusive end so "shifts intersecting the window" matches the
            // schedule views.
            return switch (this) {
                case WEEK -> start(today).plusDays(7);
                case MONTH -> start(today).plusMonths(1);
                case QUARTER -> start(today).plusMonths(3);
            };
        }

        LocalDate previousStart(LocalDate today) {
            return switch (this) {
                case WEEK -> start(today).minusDays(7);
                case MONTH -> start(today).minusMonths(1);
                case QUARTER -> start(today).minusMonths(3);
            };
        }

        String label(LocalDate today, DateTimeFormatter formatter) {
            return formatter.format(start(today)) + " – " + formatter.format(end(today).minusDays(1));
        }
    }
}
