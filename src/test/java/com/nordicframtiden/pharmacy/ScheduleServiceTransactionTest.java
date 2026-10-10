package com.nordicframtiden.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

/**
 * The schedule wizard calls create() inside its own transaction and catches
 * ShiftConflictException to skip booked days. If the conflict marked the
 * shared transaction rollback-only, the wizard's commit would fail and every
 * created shift would be lost. Spring's interceptor consults exactly this
 * attribute when the exception leaves create().
 */
class ScheduleServiceTransactionTest {

  private TransactionAttribute createAttribute() throws NoSuchMethodException {
    Method create = ScheduleService.class.getMethod("create",
        Long.class, Long.class, OffsetDateTime.class, OffsetDateTime.class, String.class);
    return new AnnotationTransactionAttributeSource().getTransactionAttribute(create, ScheduleService.class);
  }

  @Test
  void day_conflict_does_not_mark_the_callers_transaction_rollback_only() throws Exception {
    assertThat(createAttribute().rollbackOn(new ShiftConflictException("booked"))).isFalse();
  }

  @Test
  void other_failures_still_roll_back() throws Exception {
    assertThat(createAttribute().rollbackOn(new IllegalArgumentException("bad"))).isTrue();
    assertThat(createAttribute().rollbackOn(new ShiftLockedException("past"))).isTrue();
  }
}
