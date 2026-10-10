package com.nordicframtiden.pharmacy;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class PharmacyService {

  private final PharmacyRepository repo;
  private final ScheduleShiftRepository shiftRepo;
  private final java.time.Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public PharmacyService(PharmacyRepository repo, ScheduleShiftRepository shiftRepo) {
    this(repo, shiftRepo, java.time.Clock.system(ShiftLockPolicy.ZONE));
  }

  PharmacyService(PharmacyRepository repo, ScheduleShiftRepository shiftRepo, java.time.Clock clock) {
    this.repo = repo;
    this.shiftRepo = shiftRepo;
    this.clock = clock;
  }

  public List<Pharmacy> list() {
    return repo.findAllNewestFirst();
  }

  @Transactional
  public Pharmacy create(PharmacyCreateRequest req) {
    String name = safe(req.name());
    if (name.isEmpty())
      throw new IllegalArgumentException("Name is required");
    if (repo.existsByNameIgnoreCase(name))
      throw new IllegalArgumentException("Pharmacy name already exists");

    Pharmacy p = new Pharmacy();
    p.setName(name);
    p.setEmail(safe(req.email()));
    p.setPhone(safe(req.phone()));
    p.setAddress(safe(req.address()));
    p.setHourlyCost(req.hourlyCost());
    p.setContactName(safe(req.contactName()));
    p.setContactEmail(safe(req.contactEmail()));
    p.setContactPhone(safe(req.contactPhone()));

    p.setEnabled(req.enabled() == null || req.enabled());
    return repo.save(p);
  }

  @Transactional
  public Pharmacy update(Long id, PharmacyUpdateRequest req) {
    Pharmacy p = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Pharmacy not found"));

    if (req.name() != null && !safe(req.name()).isEmpty() && !safe(req.name()).equalsIgnoreCase(p.getName())) {
      if (repo.existsByNameIgnoreCase(req.name()))
        throw new IllegalArgumentException("Pharmacy name already exists");
      p.setName(safe(req.name()));
    }
    if (req.hourlyCost() != null) {
      p.setHourlyCost(req.hourlyCost());
    }
    if (req.email() != null)
      p.setEmail(safe(req.email()));
    if (req.phone() != null)
      p.setPhone(safe(req.phone()));
    if (req.address() != null)
      p.setAddress(safe(req.address()));

    if (req.contactName() != null)
      p.setContactName(safe(req.contactName()));
    if (req.contactEmail() != null)
      p.setContactEmail(safe(req.contactEmail()));
    if (req.contactPhone() != null)
      p.setContactPhone(safe(req.contactPhone()));

    if (req.enabled() != null)
      p.setEnabled(req.enabled());

    return repo.save(p);
  }

  @Transactional
  public void delete(Long id) {
    Pharmacy p = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("Pharmacy not found"));
    // schedule_shift.pharmacy_id is ON DELETE CASCADE: deleting a pharmacy
    // with worked shifts would silently erase locked payroll history.
    java.time.OffsetDateTime todayStart = java.time.LocalDate.now(clock)
        .atStartOfDay(ShiftLockPolicy.ZONE).toOffsetDateTime();
    if (shiftRepo.existsWorkedShiftAtPharmacy(id, todayStart)) {
      throw new ShiftLockedException(
          "Apoteket har genomförda arbetspass och kan inte tas bort — historiken är låst för löne- och "
              + "revisionsändamål. Inaktivera apoteket i stället.");
    }
    repo.delete(p);
  }

  public PharmacyStats stats() {
    long enabled = repo.countByEnabledTrue();
    long disabled = repo.countByEnabledFalse();
    return new PharmacyStats(enabled, disabled, enabled + disabled);
  }

  private static String safe(String s) {
    return s == null ? "" : s.trim();
  }

  // DTO records used by the service (keep them here or move to controller)
  public record PharmacyCreateRequest(
      String name,
      String email,
      String phone,
      String address,
      String contactName,
      String contactEmail,
      String contactPhone,
      BigDecimal hourlyCost, // ✅
      Boolean enabled) {
  }

  public record PharmacyUpdateRequest(
      String name,
      String email,
      String phone,
      String address,
      String contactName,
      String contactEmail,
      String contactPhone,
      BigDecimal hourlyCost, // ✅
      Boolean enabled) {
  }

  public record PharmacyStats(long enabled, long disabled, long total) {
  }
}