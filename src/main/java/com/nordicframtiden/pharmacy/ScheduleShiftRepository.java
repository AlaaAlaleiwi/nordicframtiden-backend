package com.nordicframtiden.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public interface ScheduleShiftRepository extends JpaRepository<ScheduleShift, Long> {

    

@Query("""
  select s from ScheduleShift s
  where s.startAt < :end and s.endAt > :start
    and (:pharmacyId is null or s.pharmacy.id = :pharmacyId)
    and (:userId is null or s.user.id = :userId)
""")
List<ScheduleShift> findInRange(
  @Param("start") OffsetDateTime start,
  @Param("end") OffsetDateTime end,
  @Param("pharmacyId") Long pharmacyId,
  @Param("userId") Long userId
);

/** Any other shift of this user intersecting the [start, end) window (day-conflict check). */
@Query("""
  select s from ScheduleShift s
  where s.user.id = :userId
    and s.startAt < :end
    and s.endAt > :start
""")
List<ScheduleShift> findByUserIdAndStartAtLessThanAndEndAtGreaterThan(
  @Param("userId") Long userId,
  @Param("end") OffsetDateTime end,
  @Param("start") OffsetDateTime start
);

/** True when the pharmacy has any shift on a day before {@code before} (locked history). */
@Query("""
  select count(s) > 0 from ScheduleShift s
  where s.pharmacy.id = :pharmacyId and s.startAt < :before
""")
boolean existsWorkedShiftAtPharmacy(
  @Param("pharmacyId") Long pharmacyId,
  @Param("before") OffsetDateTime before
);
}
