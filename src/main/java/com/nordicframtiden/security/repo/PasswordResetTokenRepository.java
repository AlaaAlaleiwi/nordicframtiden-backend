package com.nordicframtiden.security.repo;

import com.nordicframtiden.security.model.PasswordResetToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

  Optional<PasswordResetToken> findByTokenHash(String tokenHash);

  /** Row-locked lookup so two concurrent submissions cannot both consume one token. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from PasswordResetToken t where t.tokenHash = :tokenHash")
  Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

  void deleteByUserId(Long userId);
}
