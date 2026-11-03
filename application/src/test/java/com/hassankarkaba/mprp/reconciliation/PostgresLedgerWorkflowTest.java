package com.hassankarkaba.mprp.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real commit-time checks. This suite must target its own disposable verification database. */
@EnabledIfEnvironmentVariable(named = "MPRP_MAIN_TEST_JDBC", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresLedgerWorkflowTest {
  private String url, ownerPassword, runtimePassword;

  @BeforeAll
  void migrate() {
    url = System.getenv("MPRP_MAIN_TEST_JDBC");
    ownerPassword = System.getenv("MPRP_MAIN_TEST_OWNER_PASSWORD");
    runtimePassword = System.getenv("MPRP_MAIN_TEST_RUNTIME_PASSWORD");
    assertTrue(url.contains("mprp_java_verify"), "Refuse a database without verification sentinel");
    Flyway.configure()
        .dataSource(url, "mprp_owner", ownerPassword)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private Connection owner() throws SQLException {
    return DriverManager.getConnection(url, "mprp_owner", ownerPassword);
  }

  private Connection runtime() throws SQLException {
    return DriverManager.getConnection(url, "mprp_runtime", runtimePassword);
  }

  private void exec(Connection c, String sql, Object... args) throws SQLException {
    try (var q = c.prepareStatement(sql)) {
      for (int n = 0; n < args.length; n++) q.setObject(n + 1, args[n]);
      q.executeUpdate();
    }
  }

  private long count(Connection c, String sql, Object... args) throws SQLException {
    try (var q = c.prepareStatement(sql)) {
      for (int n = 0; n < args.length; n++) q.setObject(n + 1, args[n]);
      try (var r = q.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }

  private record Scope(UUID merchant, UUID actor) {}

  private Scope scope() throws SQLException {
    UUID m = UUID.randomUUID(), u = UUID.randomUUID();
    try (var c = owner()) {
      exec(
          c,
          "insert into merchant(id,name,status,provider_account_id,bank_account_id)"
              + " values(?,'Isolated verification','ACTIVE',?,?)",
          m,
          m,
          m);
      exec(
          c,
          "insert into app_user(id,email,verified,totp_confirmed) values(?,?,true,true)",
          u,
          u + "@example.test");
      exec(
          c,
          "insert into membership(id,user_id,merchant_id,role) values(?,?,?,'OWNER')",
          UUID.randomUUID(),
          u,
          m);
      exec(
          c,
          "insert into accounting_period(merchant_id,month,status) values(?,'2026-10-01','OPEN')",
          m);
    }
    return new Scope(m, u);
  }

  private UUID source(Scope scope, String event, long amount, String provider, String batch)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (var c = owner()) {
      exec(
          c,
          "insert into"
              + " economic_source(id,merchant_id,provider_source_id,event_type,amount_minor,occurred_at,batch_id,resource_id,posting_month)"
              + " values(?,?,?,?,?,'2026-10-05T00:00:00Z',?,?,'2026-10-01')",
          id,
          scope.merchant,
          provider,
          event,
          amount,
          batch,
          UUID.randomUUID());
    }
    return id;
  }

  private UUID journal(
      Connection c, Scope scope, UUID source, long debit, long credit, UUID entryMerchant)
      throws SQLException {
    UUID j = UUID.randomUUID();
    exec(
        c,
        "insert into journal(id,merchant_id,source_id,purpose,sequence,period_month)"
            + " values(?,?,?,'ECONOMIC',1,'2026-10-01')",
        j,
        scope.merchant,
        source);
    exec(
        c,
        "insert into ledger_entry(id,journal_id,merchant_id,account,debit,credit)"
            + " values(?,?,?,'processor_clearing',?,0),(?,?,?,'merchant_payable',0,?)",
        UUID.randomUUID(),
        j,
        entryMerchant,
        debit,
        UUID.randomUUID(),
        j,
        scope.merchant,
        credit);
    return j;
  }

  private UUID line(
      Scope s,
      String kind,
      String event,
      long amount,
      String source,
      String batch,
      String reference,
      String account)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (var c = owner()) {
      exec(
          c,
          "insert into"
              + " statement_line(id,merchant_id,kind,account_id,line_id,source_id,event_type,reference,batch_id,occurred_at,amount_minor,currency,row_hash,canonical_json)"
              + " values(?,?,?,?,?,?,?,?,?,'2026-10-05T00:00:00Z',?,'USD',?,'{}')",
          id,
          s.merchant,
          kind,
          account,
          UUID.randomUUID().toString(),
          source,
          event,
          reference,
          batch,
          amount,
          "a".repeat(64));
    }
    return id;
  }

  private UUID group(Connection c, Scope s) throws SQLException {
    UUID g = UUID.randomUUID();
    exec(c, "insert into match_group(id,merchant_id,actor) values(?,?,?)", g, s.merchant, s.actor);
    return g;
  }

  private void allocate(Connection c, UUID g, UUID l, UUID s, long a) throws SQLException {
    exec(
        c,
        "insert into allocation(id,group_id,line_id,source_id,amount_minor) values(?,?,?,?,?)",
        UUID.randomUUID(),
        g,
        l,
        s,
        a);
  }

  @Test
  void balancedJournalCommitsAndRuntimeCannotEditOrUseDdl() throws Exception {
    Scope s = scope();
    UUID source = source(s, "CAPTURE", 100, "capture_" + UUID.randomUUID(), "b");
    UUID j;
    try (var c = runtime()) {
      c.setAutoCommit(false);
      j = journal(c, s, source, 100, 100, s.merchant);
      c.commit();
      assertEquals(2, count(c, "select count(*) from ledger_entry where journal_id=?", j));
      assertThrows(
          SQLException.class, () -> exec(c, "update journal set purpose='changed' where id=?", j));
      c.rollback();
      assertThrows(SQLException.class, () -> exec(c, "create table forbidden_runtime_ddl(id int)"));
      c.rollback();
      assertThrows(SQLException.class, () -> exec(c, "create temporary table journal(id int)"));
      c.rollback();
    }
  }

  @Test
  void unbalancedJournalFailsAtRealCommit() throws Exception {
    Scope s = scope();
    UUID source = source(s, "CAPTURE", 100, "capture_" + UUID.randomUUID(), "b");
    try (var c = runtime()) {
      c.setAutoCommit(false);
      UUID j = journal(c, s, source, 100, 99, s.merchant);
      assertThrows(SQLException.class, c::commit);
      c.rollback();
      assertEquals(0, count(c, "select count(*) from journal where id=?", j));
    }
  }

  @Test
  void crossTenantAndClosedPeriodCannotPost() throws Exception {
    Scope a = scope(), b = scope();
    UUID source = source(a, "CAPTURE", 100, "capture_" + UUID.randomUUID(), "b");
    try (var c = runtime()) {
      c.setAutoCommit(false);
      journal(c, a, source, 100, 100, b.merchant);
      assertThrows(SQLException.class, c::commit);
      c.rollback();
    }
    try (var c = owner()) {
      exec(c, "update accounting_period set status='CLOSED' where merchant_id=?", a.merchant);
    }
    try (var c = runtime()) {
      c.setAutoCommit(false);
      journal(c, a, source, 100, 100, a.merchant);
      assertThrows(SQLException.class, c::commit);
      c.rollback();
    }
  }

  @Test
  void sourceUniquenessHoldsUnderSeparateConcurrentConnections() throws Exception {
    Scope s = scope();
    String provider = "concurrent_" + UUID.randomUUID();
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int n = 0; n < 2; n++)
        futures.add(
            pool.submit(
                () -> {
                  try (var c = runtime()) {
                    c.setAutoCommit(false);
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                      exec(
                          c,
                          "insert into"
                              + " economic_source(id,merchant_id,provider_source_id,event_type,amount_minor,occurred_at,batch_id,resource_id,posting_month)"
                              + " values(?,?,?,'CAPTURE',100,'2026-10-05T00:00:00Z','batch',?,'2026-10-01')",
                          UUID.randomUUID(),
                          s.merchant,
                          provider,
                          UUID.randomUUID());
                      c.commit();
                      return true;
                    } catch (SQLException expected) {
                      c.rollback();
                      return false;
                    }
                  }
                }));
      int successes = 0;
      for (var f : futures) if (f.get(10, TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes);
      try (var c = runtime()) {
        assertEquals(
            1,
            count(
                c,
                "select count(*) from economic_source where merchant_id=? and provider_source_id=?",
                s.merchant,
                provider));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void groupedOneToManyCommitsButOvercapacityAndIncompleteGroupFail() throws Exception {
    Scope s = scope();
    UUID source1 = source(s, "CAPTURE", 100, "s1_" + UUID.randomUUID(), "batch_group"),
        source2 = source(s, "CAPTURE", 50, "s2_" + UUID.randomUUID(), "batch_group");
    UUID line =
        line(
            s,
            "PROCESSOR_ECONOMIC",
            "CAPTURE",
            150,
            "batch_group",
            "batch_group",
            "batch_group",
            s.merchant.toString());
    try (var c = runtime()) {
      c.setAutoCommit(false);
      UUID g = group(c, s);
      allocate(c, g, line, source1, 100);
      allocate(c, g, line, source2, 50);
      c.commit();
      assertEquals(2, count(c, "select count(*) from allocation where group_id=?", g));
    }
    UUID extra =
        line(
            s,
            "PROCESSOR_ECONOMIC",
            "CAPTURE",
            1,
            "batch_group",
            "batch_group",
            "batch_group",
            s.merchant.toString());
    try (var c = runtime()) {
      c.setAutoCommit(false);
      UUID g = group(c, s);
      assertThrows(SQLException.class, () -> allocate(c, g, extra, source1, 1));
      c.rollback();
    }
    UUID fresh = source(s, "CAPTURE", 200, "fresh", "fresh_batch"),
        partial =
            line(
                s,
                "PROCESSOR_ECONOMIC",
                "CAPTURE",
                100,
                "fresh",
                "fresh_batch",
                "fresh",
                s.merchant.toString());
    try (var c = runtime()) {
      c.setAutoCommit(false);
      UUID g = group(c, s);
      allocate(c, g, partial, fresh, 50);
      assertThrows(SQLException.class, c::commit);
      c.rollback();
    }
  }

  @Test
  void allocationRejectsWrongAccountSignAndCrossTenant() throws Exception {
    Scope s = scope(), other = scope();
    UUID source = source(s, "CAPTURE", 100, "same", "batch");
    UUID wrong =
        line(
            s,
            "PROCESSOR_ECONOMIC",
            "CAPTURE",
            100,
            "same",
            "batch",
            "same",
            other.merchant.toString());
    try (var c = runtime()) {
      c.setAutoCommit(false);
      UUID g = group(c, s);
      assertThrows(SQLException.class, () -> allocate(c, g, wrong, source, 100));
      c.rollback();
    }
    UUID neg = source(s, "REFUND", -100, "negative", "batch");
    UUID positive =
        line(
            s,
            "PROCESSOR_ECONOMIC",
            "CAPTURE",
            100,
            "negative",
            "batch",
            "negative",
            s.merchant.toString());
    try (var c = runtime()) {
      c.setAutoCommit(false);
      UUID g = group(c, s);
      assertThrows(SQLException.class, () -> allocate(c, g, positive, neg, 100));
      c.rollback();
    }
    UUID foreign = source(other, "CAPTURE", 100, "foreign", "batch");
    UUID own =
        line(
            s,
            "PROCESSOR_ECONOMIC",
            "CAPTURE",
            100,
            "foreign",
            "batch",
            "foreign",
            s.merchant.toString());
    try (var c = runtime()) {
      c.setAutoCommit(false);
      UUID g = group(c, s);
      assertThrows(SQLException.class, () -> allocate(c, g, own, foreign, 100));
      c.rollback();
    }
  }

  @Test
  void zeroBatchReferenceAliasIsAcceptedButDuplicateAllocationFails() throws Exception {
    Scope s = scope();
    String batch = UUID.randomUUID().toString();
    UUID source = source(s, "BATCH_ZERO", 0, batch, batch);
    UUID line =
        line(
            s,
            "PROCESSOR_TRANSFER",
            "BATCH_ZERO",
            0,
            batch,
            batch,
            "permitted_alias",
            s.merchant.toString());
    try (var c = runtime()) {
      c.setAutoCommit(false);
      UUID g = group(c, s);
      allocate(c, g, line, source, 0);
      c.commit();
      UUID g2 = group(c, s);
      assertThrows(SQLException.class, () -> allocate(c, g2, line, source, 0));
      c.rollback();
    }
  }

  @Test
  void mixedLayersAndManyToManyGroupFail() throws Exception {
    Scope s = scope();
    UUID a = source(s, "CAPTURE", 50, "mixed_a", "mixed"),
        b = source(s, "CAPTURE", 50, "mixed_b", "mixed");
    UUID
        l1 =
            line(
                s,
                "PROCESSOR_ECONOMIC",
                "CAPTURE",
                50,
                "mixed",
                "mixed",
                "mixed",
                s.merchant.toString()),
        l2 =
            line(
                s,
                "PROCESSOR_ECONOMIC",
                "CAPTURE",
                50,
                "mixed",
                "mixed",
                "mixed",
                s.merchant.toString());
    try (var c = runtime()) {
      c.setAutoCommit(false);
      UUID g = group(c, s);
      allocate(c, g, l1, a, 50);
      allocate(c, g, l2, b, 50);
      assertThrows(SQLException.class, c::commit);
      c.rollback();
    }
  }

  @Test
  void closedPeriodCannotReverseAndConfirmedLineCannotChange() throws Exception {
    Scope s = scope();
    UUID source = source(s, "CAPTURE", 100, "closed_reversal", "batch");
    UUID line =
        line(
            s,
            "PROCESSOR_ECONOMIC",
            "CAPTURE",
            100,
            "closed_reversal",
            "batch",
            "closed_reversal",
            s.merchant.toString());
    UUID group;
    try (var c = runtime()) {
      c.setAutoCommit(false);
      group = group(c, s);
      allocate(c, group, line, source, 100);
      c.commit();
    }
    try (var c = owner()) {
      exec(c, "update accounting_period set status='CLOSED' where merchant_id=?", s.merchant);
    }
    try (var c = runtime()) {
      c.setAutoCommit(false);
      assertThrows(
          SQLException.class,
          () -> exec(c, "update match_group set status='REVERSED' where id=?", group));
      c.rollback();
      assertThrows(
          SQLException.class,
          () -> exec(c, "update statement_line set amount_minor=1 where id=?", line));
      c.rollback();
      assertEquals(
          1, count(c, "select count(*) from match_group where id=? and status='MATCHED'", group));
    }
  }

  @Test
  void transferAndBankLayersHaveIndependentSourceCapacity() throws Exception {
    Scope s = scope();
    UUID source = source(s, "SETTLEMENT", 100, "transfer_source", "batch");
    UUID processor =
        line(
            s,
            "PROCESSOR_TRANSFER",
            "SETTLEMENT",
            100,
            "transfer_source",
            "batch",
            "transfer_source",
            s.merchant.toString());
    UUID bank =
        line(
            s,
            "BANK",
            "BANK",
            100,
            "transfer_source",
            "",
            "transfer_source",
            s.merchant.toString());
    try (var c = runtime()) {
      c.setAutoCommit(false);
      UUID g = group(c, s);
      allocate(c, g, processor, source, 100);
      c.commit();
      UUID b = group(c, s);
      allocate(c, b, bank, source, 100);
      c.commit();
      assertEquals(2, count(c, "select count(*) from allocation where source_id=?", source));
    }
  }
}
