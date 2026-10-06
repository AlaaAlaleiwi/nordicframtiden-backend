package com.nordicframtiden.availability;

import static org.assertj.core.api.Assertions.assertThat;

import com.nordicframtiden.security.model.AppUser;
import com.nordicframtiden.security.model.UserProfile;
import com.nordicframtiden.security.repo.AppUserRepository;
import com.nordicframtiden.security.repo.UserProfileRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AvailabilityRequestRepositoryIT {

  @Autowired private AvailabilityRequestRepository requests;
  @Autowired private AppUserRepository users;
  @Autowired private UserProfileRepository profiles;

  private Long userId;
  private Long pendingId;
  private Long approvedId;

  @BeforeEach
  void setUp() {
    AppUser user = new AppUser();
    user.setUsername("availability-it-" + System.nanoTime());
    user.setPasswordHash("unused");
    userId = users.saveAndFlush(user).getId();

    UserProfile profile = new UserProfile();
    profile.setUser(users.findById(userId).orElseThrow());
    profile.setFullName("Availability Test");
    profile.setEmail("availability-" + userId + "@example.test");
    profile.setPhone("+4670" + userId);
    profile.setYearOfBirth(1990);
    profile.setCountyCode("01");
    profile.setMunicipalityCode("0114");
    profiles.saveAndFlush(profile);

    pendingId = saveRequest(AvailabilityRequest.Status.PENDING, LocalDate.parse("2026-10-06"));
    approvedId = saveRequest(AvailabilityRequest.Status.APPROVED, LocalDate.parse("2026-10-07"));
  }

  @AfterEach
  void tearDown() {
    if (userId != null) users.deleteById(userId);
  }

  private Long saveRequest(AvailabilityRequest.Status status, LocalDate date) {
    AvailabilityRequest request = new AvailabilityRequest();
    request.setUser(users.findById(userId).orElseThrow());
    request.setType(AvailabilityRequest.Type.DAY);
    request.setStartDate(date);
    request.setEndDate(date);
    request.setStatus(status);
    request.setStartTime(LocalTime.of(9, 0));
    request.setEndTime(LocalTime.of(17, 0));
    return requests.saveAndFlush(request).getId();
  }

  @Test
  void projectionsReturnDetailsInNewerFirstOrder() {
    var summaries = requests.findSummariesByUserIdOrderByCreatedAtDesc(userId);

    assertThat(summaries).hasSize(2);
    assertThat(summaries).extracting(AvailabilityRequestRepository.AvailabilityRequestSummary::getUserFullName)
        .containsOnly("Availability Test");
    assertThat(summaries.get(0).getId()).isEqualTo(approvedId);
    assertThat(summaries.get(1).getId()).isEqualTo(pendingId);
    assertThat(requests.findAllSummariesOrderByCreatedAtDesc())
        .extracting(AvailabilityRequestRepository.AvailabilityRequestSummary::getId)
        .contains(approvedId, pendingId);
    assertThat(requests.findSummaryById(pendingId)).get()
        .extracting(AvailabilityRequestRepository.AvailabilityRequestSummary::getUserFullName)
        .isEqualTo("Availability Test");
  }

  @Test
  void overlappingSummaryQueriesReturnOnlyRequestedStatuses() {
    LocalDate start = LocalDate.parse("2026-10-06");
    LocalDate end = LocalDate.parse("2026-10-07");

    assertThat(requests.findApprovedOverlappingSummaries(start, end))
        .extracting(AvailabilityRequestRepository.AvailabilityRequestSummary::getId)
        .containsExactly(approvedId);
    assertThat(requests.findByStatusInAndOverlappingSummaries(
        java.util.List.of(AvailabilityRequest.Status.PENDING, AvailabilityRequest.Status.APPROVED), start, end))
        .extracting(AvailabilityRequestRepository.AvailabilityRequestSummary::getId)
        .containsExactlyInAnyOrder(pendingId, approvedId);
  }
}
