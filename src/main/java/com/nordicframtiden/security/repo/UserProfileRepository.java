package com.nordicframtiden.security.repo;

import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.model.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

  Optional<UserProfile> findByUserId(Long userId);

  @Query("""
      select p.user.id as userId, p.fullName as fullName, p.hourlyCost as hourlyCost,
             p.payType as payType, p.monthlySalary as monthlySalary
      from UserProfile p
      where p.user.id in :userIds
      """)
  List<UserProfileSummary> findSummariesByUserIdIn(@Param("userIds") Collection<Long> userIds);

  interface UserProfileSummary {
    Long getUserId();
    String getFullName();
    BigDecimal getHourlyCost();
    String getPayType();
    BigDecimal getMonthlySalary();
  }

  @Query("""
      select p.user.id as userId, p.fullName as fullName, p.monthlySalary as monthlySalary
      from UserProfile p join p.user.roles r
      where r = :role and upper(p.payType) = 'MONTHLY' and p.monthlySalary is not null
      """)
  List<MonthlySalaryProfile> findMonthlySalaryProfilesByRole(@Param("role") Role role);

  interface MonthlySalaryProfile {
    Long getUserId();
    String getFullName();
    BigDecimal getMonthlySalary();
  }

  boolean existsByEmail(String email);
  boolean existsByPhone(String phone);

  Optional<UserProfile> findByEmailIgnoreCase(String email);
}