package com.nordicframtiden.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tax-table cache must be cleared only after the import commits: clearing
 * it inside the transaction lets a concurrent lookup re-cache the old rows.
 */
class TaxTableImportStoreTest {

  private Cache cache;
  private TaxTableImportStore store;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    cache = mock(Cache.class);
    CacheManager cacheManager = mock(CacheManager.class);
    when(cacheManager.getCache("taxTableRows")).thenReturn(cache);
    ObjectProvider<CacheManager> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(cacheManager);
    store = new TaxTableImportStore(mock(JdbcTemplate.class), provider);
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void replaceYear_evictsCacheOnlyAfterCommit() {
    TransactionSynchronizationManager.initSynchronization();

    store.replaceYear(2026, List.of());

    verify(cache, never()).clear();
    for (TransactionSynchronization synchronization
        : TransactionSynchronizationManager.getSynchronizations()) {
      synchronization.afterCommit();
    }
    verify(cache).clear();
  }

  @Test
  void replaceYear_doesNotEvictWhenTheImportRollsBack() {
    TransactionSynchronizationManager.initSynchronization();

    store.replaceYear(2026, List.of());

    for (TransactionSynchronization synchronization
        : TransactionSynchronizationManager.getSynchronizations()) {
      synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    }
    verify(cache, never()).clear();
  }

  @Test
  void replaceYear_evictsImmediatelyWithoutATransaction() {
    store.replaceYear(2026, List.of());

    verify(cache).clear();
  }
}
