package com.nordicframtiden.availability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface AvailabilityRequestRepository extends JpaRepository<AvailabilityRequest, Long> {

  List<AvailabilityRequest> findByUserIdOrderByCreatedAtDesc(Long userId);

  long countByStatus(AvailabilityRequest.Status status);

  @Query("select r from AvailabilityRequest r order by r.createdAt desc")
  List<AvailabilityRequest> findAllOrderByCreatedAtDesc();


    @Query("""
    select a from AvailabilityRequest a
    where a.status = com.nordicframtiden.availability.AvailabilityRequest.Status.APPROVED
      and a.startDate <= :endDate
      and a.endDate >= :startDate
  """)
  List<AvailabilityRequest> findApprovedOverlapping(
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate
  );

  /**
   * Availability requests with any of the given statuses overlapping the
   * inclusive date range — lets the admin shift form consider PENDING and
   * APPROVED requests together (REJECTED never counts as available).
   */
  @Query("""
    select a from AvailabilityRequest a
    where a.status in :statuses
      and a.startDate <= :endDate
      and a.endDate >= :startDate
  """)
  List<AvailabilityRequest> findByStatusInAndOverlapping(
      @Param("statuses") List<AvailabilityRequest.Status> statuses,
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate
  );

  void deleteByUser(com.nordicframtiden.security.model.AppUser user);
}