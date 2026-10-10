package com.nordicframtiden.api;

import jakarta.validation.Valid;

import com.nordicframtiden.pharmacy.Pharmacy;
import com.nordicframtiden.pharmacy.PharmacyService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/pharmacies")
@PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
public class PharmacyController {

  private final PharmacyService service;

  public PharmacyController(PharmacyService service) {
    this.service = service;
  }

  // Requests
public record CreatePharmacyRequest(
    @NotBlank String name,
    String email,
    String phone,
    String address,
    String contactName,
    String contactEmail,
    String contactPhone,
    BigDecimal hourlyCost,   // ✅ NEW
    Boolean enabled
) {}

public record UpdatePharmacyRequest(
    String name,
    String email,
    String phone,
    String address,
    String contactName,
    String contactEmail,
    String contactPhone,
    BigDecimal hourlyCost,   // ✅ NEW
    Boolean enabled
) {}

  // Response
 public record PharmacyResponse(
    Long id,
    String name,
    String email,
    String phone,
    String address,
    String contactName,
    String contactEmail,
    String contactPhone,
    BigDecimal hourlyCost,   // ✅ NEW
    boolean enabled
) {
  static PharmacyResponse from(Pharmacy p) {
    return new PharmacyResponse(
        p.getId(),
        p.getName(),
        p.getEmail(),
        p.getPhone(),
        p.getAddress(),
        p.getContactName(),
        p.getContactEmail(),
        p.getContactPhone(),
        p.getHourlyCost(),   // ✅
        p.isEnabled()
    );
  }
}
  @GetMapping
  public org.springframework.http.ResponseEntity<List<PharmacyResponse>> list() {
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(12)).cachePrivate()) // authenticated data: never in shared caches
        .body(service.list().stream().map(PharmacyResponse::from).toList());
  }

  @PostMapping
  public PharmacyResponse create(@Valid @RequestBody CreatePharmacyRequest req) {
    Pharmacy created = service.create(new PharmacyService.PharmacyCreateRequest(
    req.name(),
    req.email(),
    req.phone(),
    req.address(),
    req.contactName(),
    req.contactEmail(),
    req.contactPhone(),
    req.hourlyCost(),   // ✅
    req.enabled()
));
    return PharmacyResponse.from(created);
  }

  @PutMapping("/{id}")
  public PharmacyResponse update(@PathVariable Long id, @Valid @RequestBody UpdatePharmacyRequest req) {
Pharmacy updated = service.update(id, new PharmacyService.PharmacyUpdateRequest(
    req.name(),
    req.email(),
    req.phone(),
    req.address(),
    req.contactName(),
    req.contactEmail(),
    req.contactPhone(),
    req.hourlyCost(),   // ✅
    req.enabled()
));
    return PharmacyResponse.from(updated);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable Long id) {
    service.delete(id);
  }

  // optional
  @GetMapping("/stats")
  public PharmacyService.PharmacyStats stats() {
    return service.stats();
  }
}