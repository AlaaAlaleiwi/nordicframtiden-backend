package com.nordicframtiden.availability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface AvailabilityRequestRepository extends JpaRepository<AvailabilityRequest, Long> {

  interface AvailabilityRequestSummary {
    Long getId();
    Long getUserId();
    String getUsername();
    String getUserFullName();
    AvailabilityRequest.Type getType();
    LocalDate getStartDate();
    LocalDate getEndDate();
    java.time.LocalTime getStartTime();
    java.time.LocalTime getEndTime();
    AvailabilityRequest.Status getStatus();
    String getNote();
  }

  @Query("""
      select a.id as id, u.id as userId, u.username as username, coalesce(p.fullName, u.username) as userFullName,
             a.type as type, a.startDate as startDate, a.endDate as endDate,
             a.startTime as startTime, a.endTime as endTime, a.status as status, a.note as note
      from AvailabilityRequest a join a.user u left join UserProfile p on p.user = u
      where u.id = :userId order by a.createdAt desc
      """)
  List<AvailabilityRequestSummary> findSummariesByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

  @Query("""
      select a.id as id, u.id as userId, u.username as username, coalesce(p.fullName, u.username) as userFullName,
             a.type as type, a.startDate as startDate, a.endDate as endDate,
             a.startTime as startTime, a.endTime as endTime, a.status as status, a.note as note
      from AvailabilityRequest a join a.user u left join UserProfile p on p.user = u
      order by a.createdAt desc
      """)
  List<AvailabilityRequestSummary> findAllSummariesOrderByCreatedAtDesc();

  @Query("""
      select a.id as id, u.id as userId, u.username as username, coalesce(p.fullName, u.username) as userFullName,
             a.type as type, a.startDate as startDate, a.endDate as endDate,
             a.startTime as startTime, a.endTime as endTime, a.status as status, a.note as note
      from AvailabilityRequest a join a.user u left join UserProfile p on p.user = u
      where a.id = :id
      """)
  java.util.Optional<AvailabilityRequestSummary> findSummaryById(@Param("id") Long id);

  @Query("""
      select a.id as id, u.id as userId, u.username as username, coalesce(p.fullName, u.username) as userFullName,
             a.type as type, a.startDate as startDate, a.endDate as endDate,
             a.startTime as startTime, a.endTime as endTime, a.status as status, a.note as note
      from AvailabilityRequest a join a.user u left join UserProfile p on p.user = u
      where a.status = com.nordicframtiden.availability.AvailabilityRequest.Status.APPROVED
        and a.startDate <= :endDate and a.endDate >= :startDate
      """)
  List<AvailabilityRequestSummary> findApprovedOverlappingSummaries(
      @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

  @Query("""
      select a.id as id, u.id as userId, u.username as username, coalesce(p.fullName, u.username) as userFullName,
             a.type as type, a.startDate as startDate, a.endDate as endDate,
             a.startTime as startTime, a.endTime as endTime, a.status as status, a.note as note
      from AvailabilityRequest a join a.user u left join UserProfile p on p.user = u
      where a.status in :statuses and a.startDate <= :endDate and a.endDate >= :startDate
      """)
  List<AvailabilityRequestSummary> findByStatusInAndOverlappingSummaries(
      @Param("statuses") List<AvailabilityRequest.Status> statuses,
      @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

  List<AvailabilityRequest> findByUserIdOrderByCreatedAtDesc(Long userId);

  long countByStatus(AvailabilityRequest.Status status);

  @Query("select r from AvailabilityRequest r order by r.createdAt desc")
  List<AvailabilityRequest> findAllOrderByCreatedAtDesc();

  @Query("""
      select a from AvailabilityRequest a
      where a.status = com.nordicframtiden.availability.AvailabilityRequest.Status.APPROVED
        and a.startDate <= :endDate and a.endDate >= :startDate
      """)
  List<AvailabilityRequest> findApprovedOverlapping(
      @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

  /** Availability rows in an inclusive date range, filtered by one or more statuses. */
  @Query("""
      select a from AvailabilityRequest a
      where a.status in :statuses and a.startDate <= :endDate and a.endDate >= :startDate
      """)
  List<AvailabilityRequest> findByStatusInAndOverlapping(
      @Param("statuses") List<AvailabilityRequest.Status> statuses,
      @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

  void deleteByUser(com.nordicframtiden.security.model.AppUser user);
}