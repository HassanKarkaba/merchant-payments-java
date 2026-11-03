package com.hassankarkaba.mprp.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import com.hassankarkaba.mprp.operations.*;
import com.hassankarkaba.mprp.settlements.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.*;

/** Real Spring proxy/PG transactions; request authority is a fixture, not a login/MFA claim. */
@EnabledIfEnvironmentVariable(named = "MPRP_MAIN_TEST_JDBC", matches = ".+")
@SpringBootTest(
    properties = {
      "mprp.worker=false",
      "mprp.command=serve",
      "spring.flyway.enabled=false",
      "spring.kafka.admin.auto-create=false",
      "spring.kafka.listener.auto-startup=false",
      "spring.mail.host=127.0.0.1"
    })
class WorkflowServiceIntegrationTest {
  @Autowired SettlementService service;
  @Autowired Db db;
  @Autowired MatchService matches;
  @Autowired CorrectionService corrections;
  @Autowired PeriodService periods;
  @Autowired StatementService statements;
  @Autowired com.hassankarkaba.mprp.disputes.DisputeService disputes;
  @Autowired com.hassankarkaba.mprp.payments.PaymentService payments;
  @Autowired com.hassankarkaba.mprp.accounting.LedgerService ledger;
  @Autowired MatchingJobService matchingJobs;
  @Autowired WorkflowNotice notice;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  private static final String URL = System.getenv("MPRP_MAIN_TEST_JDBC"),
      OWNER_PASSWORD = System.getenv("MPRP_MAIN_TEST_OWNER_PASSWORD"),
      RUNTIME_PASSWORD = System.getenv("MPRP_MAIN_TEST_RUNTIME_PASSWORD");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    assertTrue(URL.contains("mprp_java_verify"));
    Flyway.configure()
        .dataSource(URL, "mprp_owner", OWNER_PASSWORD)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    r.add("spring.datasource.url", () -> URL);
    r.add("spring.datasource.username", () -> "mprp_runtime");
    r.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
    r.add(
        "mprp.environment-id",
        () ->
            System.getenv()
                .getOrDefault(
                    "MPRP_MAIN_TEST_ENVIRONMENT_ID", "c963b2e7-aea4-4d39-89cc-5ae1aa2cb69f"));
    r.add("mprp.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    r.add("MPRP_SERVICE_KEY_ACTIVE_FROM", () -> "2026-01-01T00:00:00Z");
    r.add("MPRP_SERVICE_KEY_EXPIRES_AT", () -> "2027-01-01T00:00:00Z");
    r.add("mprp.service-secret", () -> "isolated-test-service-secret-never-for-deployment-123456");
    r.add("mprp.simulator-url", () -> "http://127.0.0.1:65534");
    r.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:65533");
  }

  private void exec(Connection c, String sql, Object... args) throws SQLException {
    try (var q = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) q.setObject(i + 1, args[i]);
      q.executeUpdate();
    }
  }

  private record Scope(UUID merchant, UUID maker, UUID checker, UUID checker2) {}

  private Scope scope() throws SQLException {
    UUID m = UUID.randomUUID(),
        maker = UUID.randomUUID(),
        checker = UUID.randomUUID(),
        checker2 = UUID.randomUUID();
    try (var c = DriverManager.getConnection(URL, "mprp_owner", OWNER_PASSWORD)) {
      exec(
          c,
          "insert into"
              + " merchant(id,name,status,provider_account_id,bank_account_id,ledger_sequence,funds_version)"
              + " values(?,'Service transaction fixture','ACTIVE',?,?,3,3)",
          m,
          m,
          m);
      for (UUID u : List.of(maker, checker, checker2)) {
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
      }
      exec(
          c,
          "insert into accounting_period(merchant_id,month,status) values(?,'2026-10-01','OPEN')",
          m);
      String[] types = {"CAPTURE", "FEE", "SETTLEMENT"};
      long[] amounts = {10000, -320, 9680};
      String[] debit = {"processor_clearing", "merchant_payable", "bank_control"},
          credit = {"merchant_payable", "processor_clearing", "processor_clearing"};
      c.setAutoCommit(false);
      for (int i = 0; i < types.length; i++) {
        UUID source = UUID.randomUUID(), journal = UUID.randomUUID();
        exec(
            c,
            "insert into"
                + " economic_source(id,merchant_id,provider_source_id,event_type,amount_minor,occurred_at,batch_id,resource_id,posting_month)"
                + " values(?,?,?,?,?,'2026-10-05T00:00:00Z','fixture_batch',?,'2026-10-01')",
            source,
            m,
            source.toString(),
            types[i],
            amounts[i],
            UUID.randomUUID());
        exec(
            c,
            "insert into journal(id,merchant_id,source_id,purpose,sequence,period_month)"
                + " values(?,?,?,'ECONOMIC',?,'2026-10-01')",
            journal,
            m,
            source,
            i + 1);
        long amount = Math.abs(amounts[i]);
        exec(
            c,
            "insert into ledger_entry(id,journal_id,merchant_id,account,debit,credit)"
                + " values(?,?,?,?,?,0),(?,?,?,?,0,?)",
            UUID.randomUUID(),
            journal,
            m,
            debit[i],
            amount,
            UUID.randomUUID(),
            journal,
            m,
            credit[i],
            amount);
      }
      c.commit();
    }
    db.exec("update system_control set restore_hold=false,clock_at=now() where id=true");
    return new Scope(m, maker, checker, checker2);
  }

  private void as(UUID user) {
    // Fixture prerequisites only: real Kafka/provider heartbeat qualification belongs to composed
    // tests.
    db.exec(
        "update system_control set"
            + " restore_hold=false,provider_feed_blocked=false,worker_at=now(),provider_checked_at=now(),broker_ready=true,broker_checked_at=now(),clock_at=now()"
            + " where id=true");
    var request = new MockHttpServletRequest();
    var session = request.getSession();
    session.setAttribute("epoch", 1L);
    session.setAttribute("login_at", Instant.now());
    session.setAttribute("mfa", true);
    session.setAttribute("step_up_at", Instant.now());
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(user.toString(), "", List.of()));
  }

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
    SecurityContextHolder.clearContext();
  }

  private UUID beneficiary(Scope s) throws Exception {
    as(s.maker);
    UUID b =
        service.proposeBeneficiary(
            s.merchant, "acct_demo_" + s.merchant.toString().replace("-", ""));
    as(s.checker);
    service.approveBeneficiary(s.merchant, b, 1, WorkflowSupport.key());
    return b;
  }

  private Map<String, Object> payout(UUID id) {
    return db.one("select * from payout where id=?", id);
  }

  private UUID approve(Scope s, UUID payout, UUID checker, String key) {
    var p = payout(payout);
    as(checker);
    return service.approvePayout(
        s.merchant, payout, Db.number(p, "version"), key, p.get("payload_hash").toString());
  }

  @Test
  void differentOwnerApprovesAndEquivalentReceiptReplays() throws Exception {
    Scope s = scope();
    as(s.maker);
    UUID b =
        service.proposeBeneficiary(
            s.merchant, "acct_demo_" + s.merchant.toString().replace("-", ""));
    DomainException denied =
        assertThrows(
            DomainException.class,
            () -> service.approveBeneficiary(s.merchant, b, 1, WorkflowSupport.key()));
    assertEquals(403, denied.status());
    as(s.checker);
    service.approveBeneficiary(s.merchant, b, 1, WorkflowSupport.key());
    as(s.maker);
    UUID payout = service.proposePayout(s.merchant, b, 1000, "Synthetic supplier");
    String hash = payout(payout).get("payload_hash").toString(), key = WorkflowSupport.key();
    as(s.checker);
    assertEquals(payout, service.approvePayout(s.merchant, payout, 1, key, hash));
    assertEquals(payout, service.approvePayout(s.merchant, payout, 1, key, hash));
    assertEquals(
        1,
        db.scalar(
            "select count(*) from operation where resource_id=? and kind='payout'",
            Long.class,
            payout));
    assertTrue((Boolean) payout(payout).get("reserved"));
    assertEquals(8680, service.available(s.merchant));
    assertEquals(
        409,
        assertThrows(
                DomainException.class,
                () -> service.approvePayout(s.merchant, payout, 2, key, hash))
            .status());
  }

  @Test
  void competingApprovalsCannotReserveMoreThanSettledCash() throws Exception {
    Scope s = scope();
    UUID b = beneficiary(s);
    as(s.maker);
    UUID first = service.proposePayout(s.merchant, b, 6000, "First synthetic payout"),
        second = service.proposePayout(s.merchant, b, 6000, "Second synthetic payout");
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(() -> raceApprove(s, first, s.checker, barrier));
      var c = pool.submit(() -> raceApprove(s, second, s.checker2, barrier));
      int approved = (a.get(60, TimeUnit.SECONDS) ? 1 : 0) + (c.get(60, TimeUnit.SECONDS) ? 1 : 0);
      assertEquals(1, approved);
      assertEquals(
          6000,
          db.scalar(
              "select coalesce(sum(amount_minor),0) from payout where merchant_id=? and reserved",
              Long.class,
              s.merchant));
      assertEquals(
          1,
          db.scalar(
              "select count(*) from operation where merchant_id=? and kind='payout'",
              Long.class,
              s.merchant));
      assertEquals(3680, service.available(s.merchant));
    } finally {
      pool.shutdownNow();
    }
  }

  private boolean raceApprove(Scope s, UUID payout, UUID checker, CyclicBarrier barrier)
      throws Exception {
    as(checker);
    try {
      barrier.await(5, TimeUnit.SECONDS);
      service.approvePayout(
          s.merchant,
          payout,
          1,
          WorkflowSupport.key(),
          payout(payout).get("payload_hash").toString());
      return true;
    } catch (DomainException expected) {
      assertEquals(409, expected.status());
      return false;
    } catch (org.springframework.dao.DataAccessException retryable) {
      Throwable cause = retryable;
      while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
      if (cause instanceof SQLException sql
          && Set.of("55P03", "57014", "40P01").contains(sql.getSQLState())) return false;
      throw retryable;
    } finally {
      clear();
    }
  }

  @Test
  void explicitRevisionPreservesOldKeyAndReceiptAndUsesNewOperation() throws Exception {
    Scope s = scope();
    UUID old = beneficiary(s);
    as(s.maker);
    UUID payout = service.proposePayout(s.merchant, old, 1000, "Reapproval fixture");
    String oldHash = payout(payout).get("payload_hash").toString(), oldKey = WorkflowSupport.key();
    approve(s, payout, s.checker, oldKey);
    UUID oldOperation = (UUID) payout(payout).get("operation_id");
    as(s.checker);
    UUID replacement =
        service.proposeBeneficiary(
            s.merchant, "acct_demo_" + s.merchant.toString().replace("-", ""));
    as(s.maker);
    service.approveBeneficiary(s.merchant, replacement, 1, WorkflowSupport.key());
    var invalidated = payout(payout);
    assertEquals("NEEDS_REAPPROVAL", invalidated.get("status"));
    assertFalse((Boolean) invalidated.get("reserved"));
    assertEquals(
        "CANCELLED", db.one("select status from operation where id=?", oldOperation).get("status"));
    service.revisePayout(
        s.merchant, payout, replacement, Db.number(invalidated, "version"), WorkflowSupport.key());
    var revised = payout(payout);
    assertNotEquals(oldHash, revised.get("payload_hash"));
    as(s.checker);
    assertEquals(payout, service.approvePayout(s.merchant, payout, 1, oldKey, oldHash));
    assertFalse((Boolean) payout(payout).get("reserved"));
    approve(s, payout, s.checker, WorkflowSupport.key());
    assertNotEquals(oldOperation, payout(payout).get("operation_id"));
    assertEquals(
        2,
        db.scalar(
            "select count(*) from operation where resource_id=? and kind='payout'",
            Long.class,
            payout));
    assertTrue(
        db.scalar(
                "select count(*) from payout_proposal_version where payout_id=?",
                Long.class,
                payout)
            >= 2);
  }

  private void reducePayable(Scope scope, long amount) throws Exception {
    try (var c = DriverManager.getConnection(URL, "mprp_owner", OWNER_PASSWORD)) {
      c.setAutoCommit(false);
      UUID source = UUID.randomUUID(), journal = UUID.randomUUID();
      exec(
          c,
          "insert into"
              + " economic_source(id,merchant_id,provider_source_id,event_type,amount_minor,occurred_at,batch_id,resource_id,posting_month)"
              + " values(?,?,?,'REFUND',?,'2026-10-06T00:00:00Z','fixture_refund_batch',?,'2026-10-01')",
          source,
          scope.merchant,
          source.toString(),
          -amount,
          UUID.randomUUID());
      exec(
          c,
          "insert into journal(id,merchant_id,source_id,purpose,sequence,period_month)"
              + " values(?,?,?,'ECONOMIC',4,'2026-10-01')",
          journal,
          scope.merchant,
          source);
      exec(
          c,
          "insert into ledger_entry(id,journal_id,merchant_id,account,debit,credit)"
              + " values(?,?,?,'merchant_payable',?,0),(?,?,?,'processor_clearing',0,?)",
          UUID.randomUUID(),
          journal,
          scope.merchant,
          amount,
          UUID.randomUUID(),
          journal,
          scope.merchant,
          amount);
      c.commit();
    }
  }

  private void dispatchCheck(Scope scope, UUID payout) {
    new org.springframework.transaction.support.TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              db.lockMerchant(scope.merchant);
              var p = payout(payout);
              service.validateDispatch(
                  db.one("select * from operation where id=?", p.get("operation_id")));
            });
  }

  @Test
  void refundDoesNotDeadlockAllQueuedPayoutsWhenEarlierPayoutFits() throws Exception {
    Scope scope = scope();
    UUID b = beneficiary(scope);
    as(scope.maker);
    UUID first = service.proposePayout(scope.merchant, b, 6000, "Earlier payout"),
        second = service.proposePayout(scope.merchant, b, 3000, "Later payout");
    approve(scope, first, scope.checker, WorkflowSupport.key());
    approve(scope, second, scope.checker, WorkflowSupport.key());
    reducePayable(scope, 1000);
    assertDoesNotThrow(() -> dispatchCheck(scope, first));
    assertEquals(
        503, assertThrows(DomainException.class, () -> dispatchCheck(scope, second)).status());
    assertTrue((Boolean) payout(first).get("reserved"));
    assertTrue((Boolean) payout(second).get("reserved"));
  }

  @Test
  void unfundableEarlierPayoutDoesNotStarveSmallerEligibleLaterPayout() throws Exception {
    Scope scope = scope();
    UUID b = beneficiary(scope);
    as(scope.maker);
    UUID first = service.proposePayout(scope.merchant, b, 6000, "Unfundable earlier payout"),
        second = service.proposePayout(scope.merchant, b, 3000, "Eligible smaller later payout");
    approve(scope, first, scope.checker, WorkflowSupport.key());
    approve(scope, second, scope.checker, WorkflowSupport.key());
    reducePayable(scope, 4680);
    assertEquals(
        503, assertThrows(DomainException.class, () -> dispatchCheck(scope, first)).status());
    assertDoesNotThrow(() -> dispatchCheck(scope, second));
    assertTrue((Boolean) payout(first).get("reserved"));
  }

  private UUID reconciler() throws Exception {
    UUID user = UUID.randomUUID();
    try (var c = DriverManager.getConnection(URL, "mprp_owner", OWNER_PASSWORD)) {
      exec(
          c,
          "insert into app_user(id,email,verified,totp_confirmed,platform_role)"
              + " values(?,?,true,true,'RECONCILIATION')",
          user,
          user + "@example.test");
    }
    return user;
  }

  private void matchingEvidence(Scope scope, int count) throws Exception {
    try (var c = DriverManager.getConnection(URL, "mprp_owner", OWNER_PASSWORD)) {
      c.setAutoCommit(false);
      exec(
          c,
          "insert into"
              + " economic_source(id,merchant_id,provider_source_id,event_type,amount_minor,occurred_at,batch_id,resource_id,posting_month)"
              + " select"
              + " gen_random_uuid(),?,'matching_'||n,'CAPTURE',100,'2026-10-07T00:00:00Z'::timestamptz+n*interval"
              + " '1 second','matching_batch',gen_random_uuid(),'2026-10-01'::date from"
              + " generate_series(1,?) n",
          scope.merchant,
          count);
      exec(
          c,
          "insert into journal(id,merchant_id,source_id,purpose,sequence,period_month) select"
              + " id,merchant_id,id,'ECONOMIC',3+row_number() over(order by"
              + " occurred_at,id),'2026-10-01'::date from economic_source where merchant_id=? and"
              + " batch_id='matching_batch'",
          scope.merchant);
      exec(
          c,
          "insert into ledger_entry(id,journal_id,merchant_id,account,debit,credit) select"
              + " gen_random_uuid(),id,merchant_id,'processor_clearing',100,0 from economic_source"
              + " where merchant_id=? and batch_id='matching_batch'",
          scope.merchant);
      exec(
          c,
          "insert into ledger_entry(id,journal_id,merchant_id,account,debit,credit) select"
              + " gen_random_uuid(),id,merchant_id,'merchant_payable',0,100 from economic_source"
              + " where merchant_id=? and batch_id='matching_batch'",
          scope.merchant);
      exec(
          c,
          "insert into"
              + " statement_line(id,merchant_id,kind,account_id,line_id,source_id,event_type,reference,batch_id,occurred_at,amount_minor,currency,row_hash,canonical_json)"
              + " select"
              + " gen_random_uuid(),merchant_id,'PROCESSOR_ECONOMIC',merchant_id::text,provider_source_id,provider_source_id,'CAPTURE',provider_source_id,batch_id,occurred_at,amount_minor,'USD',repeat('a',64),'{}'"
              + " from economic_source where merchant_id=? and batch_id='matching_batch'",
          scope.merchant);
      exec(
          c,
          "update merchant set ledger_sequence=3+?,funds_version=3+? where id=?",
          count,
          count,
          scope.merchant);
      UUID zero = UUID.randomUUID();
      exec(
          c,
          "insert into"
              + " economic_source(id,merchant_id,provider_source_id,event_type,amount_minor,occurred_at,batch_id,resource_id,posting_month)"
              + " values(?,?,?,'BATCH_ZERO',0,'2026-10-08T00:00:00Z',?,?,'2026-10-01')",
          zero,
          scope.merchant,
          zero.toString(),
          zero.toString(),
          zero);
      exec(
          c,
          "insert into"
              + " statement_line(id,merchant_id,kind,account_id,line_id,source_id,event_type,reference,batch_id,occurred_at,amount_minor,currency,row_hash,canonical_json)"
              + " values(?,?,'PROCESSOR_TRANSFER',?,"
              + " ?,?,'BATCH_ZERO','permitted_alias',?,'2026-10-08T00:00:00Z',0,'USD',repeat('a',64),'{}')",
          UUID.randomUUID(),
          scope.merchant,
          scope.merchant.toString(),
          "zero_" + zero,
          zero.toString(),
          zero.toString());
      c.commit();
    }
  }

  @Test
  void durableMatchingProgressesBeyondFirstChunkAndRepeatedZeroEvidenceIsSafe() throws Exception {
    Scope scope = scope();
    UUID recon = reconciler();
    matchingEvidence(scope, 501);
    as(recon);
    long
        journals =
            db.scalar(
                "select count(*) from journal where merchant_id=?", Long.class, scope.merchant),
        unfinished =
            db.scalar("select unfinished_jobs from system_control where id=true", Long.class);
    UUID run = matchingJobs.admit(scope.merchant);
    assertEquals(run, matchingJobs.admit(scope.merchant));
    assertEquals(
        unfinished + 1,
        db.scalar("select unfinished_jobs from system_control where id=true", Long.class));
    MatchingWorker worker = new MatchingWorker(db, matches, manager, notice, true);
    for (int i = 0;
        i < 20
            && !db.one("select status from matching_run where id=?", run)
                .get("status")
                .equals("SUCCEEDED");
        i++) worker.discover();
    var result = db.one("select * from matching_run where id=?", run);
    assertEquals("SUCCEEDED", result.get("status"), result.toString());
    assertEquals(502, ((Number) result.get("examined_lines")).longValue());
    assertEquals(502, ((Number) result.get("matched_groups")).longValue());
    assertEquals(
        journals,
        db.scalar("select count(*) from journal where merchant_id=?", Long.class, scope.merchant));
    assertEquals(
        unfinished,
        db.scalar("select unfinished_jobs from system_control where id=true", Long.class));
    as(recon);
    UUID replay = matchingJobs.admit(scope.merchant);
    for (int i = 0;
        i < 5
            && !db.one("select status from matching_run where id=?", replay)
                .get("status")
                .equals("SUCCEEDED");
        i++) worker.discover();
    assertEquals(
        "SUCCEEDED", db.one("select status from matching_run where id=?", replay).get("status"));
    assertEquals(
        502,
        db.scalar(
            "select count(*) from allocation a join match_group g on g.id=a.group_id where"
                + " g.merchant_id=?",
            Long.class,
            scope.merchant));
  }

  @Test
  void revokedPeriodMakerCanBeReplacedWithoutPostingAndCancellationReplays() throws Exception {
    Scope scope = scope();
    UUID original = reconciler(), replacement = reconciler(), proposal = UUID.randomUUID();
    String empty = "{}", hash = com.hassankarkaba.mprp.protocol.CanonicalJson.sha256(Map.of());
    try (var c = DriverManager.getConnection(URL, "mprp_owner", OWNER_PASSWORD)) {
      exec(
          c,
          "insert into"
              + " period_proposal(id,merchant_id,month,maker,decision_kind,snapshot_json,snapshot_hash)"
              + " values(?,?,'2026-10-01',?,'CLOSE',?,?)",
          proposal,
          scope.merchant,
          original,
          empty,
          hash);
      exec(
          c,
          "update accounting_period set status='CLOSE_PROPOSED' where merchant_id=?",
          scope.merchant);
    }
    as(replacement);
    assertEquals(
        403,
        assertThrows(
                DomainException.class,
                () ->
                    periods.cancelProposal(proposal, 1, WorkflowSupport.key(), "Maker replacement"))
            .status());
    try (var c = DriverManager.getConnection(URL, "mprp_owner", OWNER_PASSWORD)) {
      exec(c, "update app_user set active=false where id=?", original);
    }
    String key = WorkflowSupport.key();
    assertEquals(proposal, periods.cancelProposal(proposal, 1, key, "Replace revoked maker"));
    com.hassankarkaba.mprp.accounts.Authz.request().getSession().removeAttribute("step_up_at");
    assertEquals(proposal, periods.cancelProposal(proposal, 1, key, "Replace revoked maker"));
    assertEquals(
        "CANCELLED", db.one("select state from period_proposal where id=?", proposal).get("state"));
    assertEquals(
        "OPEN",
        db.one("select status from accounting_period where merchant_id=?", scope.merchant)
            .get("status"));
    assertEquals(
        3,
        db.scalar("select count(*) from journal where merchant_id=?", Long.class, scope.merchant));
  }

  @Test
  void identicalCorrectionAdoptionRetainsOriginalMakerAndRequiresIndependentApproval()
      throws Exception {
    Scope scope = scope();
    UUID original = reconciler(), replacement = reconciler(), source = UUID.randomUUID();
    try (var c = DriverManager.getConnection(URL, "mprp_owner", OWNER_PASSWORD)) {
      exec(
          c,
          "insert into"
              + " economic_source(id,merchant_id,provider_source_id,event_type,amount_minor,occurred_at,batch_id,resource_id,recognized,posting_month)"
              + " values(?,?,?,'CORRECTION_CREDIT',500,'2026-10-09T00:00:00Z','correction_batch',?,false,'2026-10-01')",
          source,
          scope.merchant,
          source.toString(),
          source);
      exec(
          c,
          "insert into"
              + " reconciliation_exception(id,merchant_id,category,source_reference,amount_minor,reason)"
              + " values(?,?,'UNRECOGNIZED_CORRECTION',?,500,'Authenticated correction awaits"
              + " recognition')",
          UUID.randomUUID(),
          scope.merchant,
          source.toString());
    }
    as(original);
    UUID proposal =
        corrections.propose(scope.merchant, source, "Exact provider-authenticated correction");
    String hash =
        db.one("select payload_hash from correction_proposal where id=?", proposal)
            .get("payload_hash")
            .toString();
    as(replacement);
    assertEquals(
        409,
        assertThrows(
                DomainException.class, () -> corrections.adopt(proposal, 1, WorkflowSupport.key()))
            .status());
    try (var c = DriverManager.getConnection(URL, "mprp_owner", OWNER_PASSWORD)) {
      exec(c, "update app_user set active=false where id=?", original);
      exec(c, "update app_user set platform_role='FINANCE_APPROVER' where id=?", scope.checker);
    }
    String adoptionKey = WorkflowSupport.key();
    corrections.adopt(proposal, 1, adoptionKey);
    assertEquals(proposal, corrections.adopt(proposal, 1, adoptionKey));
    var adopted = db.one("select * from correction_proposal where id=?", proposal);
    assertEquals(original, adopted.get("original_maker"));
    assertEquals(replacement, adopted.get("maker"));
    assertEquals(hash, adopted.get("payload_hash"));
    assertEquals(
        403,
        assertThrows(
                DomainException.class,
                () -> corrections.decide(proposal, 2, "approve", WorkflowSupport.key()))
            .status());
    as(scope.checker);
    String approvalKey = WorkflowSupport.key();
    db.exec("update system_control set restore_hold=true where id=true");
    assertEquals(
        503,
        assertThrows(
                DomainException.class,
                () -> corrections.decide(proposal, 2, "approve", approvalKey))
            .status());
    assertEquals(
        0, db.scalar("select count(*) from journal where source_id=?", Long.class, source));
    as(scope.checker);
    corrections.decide(proposal, 2, "approve", approvalKey);
    com.hassankarkaba.mprp.accounts.Authz.request().getSession().removeAttribute("step_up_at");
    db.exec("update system_control set restore_hold=true where id=true");
    assertEquals(proposal, corrections.decide(proposal, 2, "approve", approvalKey));
    assertEquals(
        1, db.scalar("select count(*) from journal where source_id=?", Long.class, source));
    assertEquals(
        3,
        db.scalar(
            "select count(*) from correction_proposal_history where proposal_id=?",
            Long.class,
            proposal));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.exec("update correction_proposal set rationale='changed' where id=?", proposal));
  }

  @Test
  void resolvedLineExceptionUsesItsSourcesAndHistoryGrowsAsRows() throws Exception {
    Scope scope = scope();
    UUID recon = reconciler();
    matchingEvidence(scope, 1);
    as(recon);
    var line =
        db.one(
            "select * from statement_line where merchant_id=? and event_type='CAPTURE'",
            scope.merchant);
    UUID
        source =
            (UUID)
                db.one(
                        "select id from economic_source where merchant_id=? and"
                            + " provider_source_id='matching_1'",
                        scope.merchant)
                    .get("id"),
        exception = UUID.randomUUID();
    db.exec(
        "insert into"
            + " reconciliation_exception(id,merchant_id,category,source_reference,line_id,amount_minor,reason)"
            + " values(?,?,'UNMATCHED_LINE',?,?,100,'Missing source at first observation')",
        exception,
        scope.merchant,
        line.get("id").toString(),
        line.get("id"));
    assertEquals(
        409,
        assertThrows(
                DomainException.class,
                () ->
                    matches.exceptionAction(
                        exception, 1, "accept", "Text does not waive a monetary difference"))
            .status());
    UUID group =
        matches.match(
            scope.merchant,
            List.of(new MatchService.Allocation((UUID) line.get("id"), source, 100)),
            "Exact source arrived");
    matches.exceptionAction(exception, 1, "resolve", "Journal and active evidence now complete");
    assertEquals(
        "RESOLVED",
        db.one("select state from reconciliation_exception where id=?", exception).get("state"));
    matches.reverse(group, 1, "Reversal invalidates current closure evidence");
    assertFalse(matches.evidenceComplete(source));
    for (int i = 0; i < 5; i++) {
      long version =
          ((Number)
                  db.one("select version from reconciliation_exception where id=?", exception)
                      .get("version"))
              .longValue();
      matches.exceptionAction(exception, version, "investigate", "\"".repeat(1000));
    }
    assertEquals(
        6,
        db.scalar(
            "select count(*) from exception_history where exception_id=?", Long.class, exception));
    assertEquals(
        "[]",
        db.one("select history_json from reconciliation_exception where id=?", exception)
            .get("history_json"));
  }

  @Test
  void stagedExpiryCommitsAndConflictingAttemptCannotWithdrawConfirmedEvidence() throws Exception {
    Scope scope = scope();
    UUID recon = reconciler();
    as(recon);
    byte[] file =
        (String.join(",", StatementParser.PROCESSOR) + "\r\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    java.time.Instant start = java.time.Instant.parse("2026-10-01T00:00:00Z"),
        end = java.time.Instant.parse("2026-11-01T00:00:00Z");
    String statement = "sim_" + UUID.randomUUID().toString().replace("-", ""),
        manifest = UUID.randomUUID().toString();
    var metadata =
        new StatementService.Metadata(
            scope.merchant,
            "PROCESSOR_ECONOMIC",
            scope.merchant.toString(),
            statement,
            manifest,
            start,
            end,
            0,
            0,
            0,
            0);
    UUID staged = statements.stage(metadata, file);
    db.exec(
        "update statement_import set expires_at=clock_timestamp()-interval '1 second' where id=?",
        staged);
    assertEquals(
        409, assertThrows(DomainException.class, () -> statements.confirm(staged, 1)).status());
    var expired = db.one("select * from statement_import where id=?", staged);
    assertEquals("EXPIRED", expired.get("status"));
    assertNull(expired.get("content_cipher"));
    assertNull(expired.get("parsed_cipher"));
    String second = "sim_" + UUID.randomUUID().toString().replace("-", "");
    UUID confirmed =
        statements.stage(
            new StatementService.Metadata(
                scope.merchant,
                "PROCESSOR_ECONOMIC",
                scope.merchant.toString(),
                second,
                manifest,
                start,
                end,
                0,
                0,
                0,
                0),
            file);
    db.exec(
        "update statement_import set"
            + " status='CONFIRMED',content_cipher=null,parsed_cipher=null,version=version+1,confirmed_at=clock_timestamp()"
            + " where id=?",
        confirmed);
    assertEquals(
        409,
        assertThrows(
                DomainException.class,
                () ->
                    statements.stage(
                        new StatementService.Metadata(
                            scope.merchant,
                            "PROCESSOR_ECONOMIC",
                            scope.merchant.toString(),
                            second,
                            UUID.randomUUID().toString(),
                            start,
                            end,
                            1,
                            1,
                            0,
                            0),
                        file))
            .status());
    var retained =
        db.one(
            "select status,manifest_id,opening_minor from statement_import where id=?", confirmed);
    assertEquals("CONFIRMED", retained.get("status"));
    assertEquals(manifest, retained.get("manifest_id"));
    assertEquals(0L, ((Number) retained.get("opening_minor")).longValue());
    assertEquals(
        1,
        db.scalar(
            "select count(*) from quarantine where kind='STATEMENT_METADATA_CONFLICT'",
            Long.class));
  }

  @Test
  void independentlyObservedDisputePostsWhileUnknownRefundReservationRemains() throws Exception {
    Scope scope = scope();
    as(scope.maker);
    UUID payment = UUID.randomUUID();
    db.exec(
        "insert into"
            + " payment(id,merchant_id,status,capture_mode,customer_name,authorized_minor,captured_minor)"
            + " values(?,?,'CAPTURED','AUTOMATIC','Fictional uncertainty fixture',10000,10000)",
        payment,
        scope.merchant);
    UUID refund =
        payments.action(
            scope.merchant,
            payment,
            "refund",
            "25.00",
            "Retained unknown reservation",
            WorkflowSupport.key());
    db.exec("update operation set status='UNKNOWN' where id=?", refund);
    UUID dispute = UUID.randomUUID();
    String occurred = com.hassankarkaba.mprp.protocol.CanonicalJson.instant(Instant.now());
    var source =
        WorkflowSupport.map(
            "source_id",
            UUID.randomUUID().toString(),
            "provider_account_id",
            scope.merchant.toString(),
            "event_type",
            "DISPUTE_OPEN",
            "amount_minor",
            -10000L,
            "occurred_at",
            occurred,
            "batch_id",
            UUID.randomUUID().toString(),
            "resource_id",
            dispute.toString(),
            "currency",
            "USD",
            "sequence",
            1);
    var result =
        WorkflowSupport.map(
            "state",
            "SUCCEEDED",
            "currency",
            "USD",
            "dispute",
            WorkflowSupport.map(
                "id",
                dispute.toString(),
                "payment_id",
                payment.toString(),
                "amount_minor",
                10000L,
                "status",
                "OPEN",
                "version",
                1,
                "response_deadline",
                com.hassankarkaba.mprp.protocol.CanonicalJson.instant(
                    Instant.now().plusSeconds(604800))),
            "sources",
            List.of(source));
    clear();
    new org.springframework.transaction.support.TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              db.lockMerchant(scope.merchant);
              ledger.apply(scope.merchant, null, List.of(source));
              disputes.apply(scope.merchant, "open_dispute", payment, result);
            });
    var observed = db.one("select * from payment where id=?", payment);
    assertEquals(10000L, ((Number) observed.get("disputed_minor")).longValue());
    assertEquals(2500L, ((Number) observed.get("reserved_refund_minor")).longValue());
    assertEquals(-320L, ledger.balances(scope.merchant).get("merchant_payable"));
    assertEquals(
        1,
        db.scalar(
            "select count(*) from journal j join economic_source s on s.id=j.source_id where"
                + " s.merchant_id=? and s.event_type='DISPUTE_OPEN'",
            Long.class,
            scope.merchant));
    as(scope.maker);
    assertEquals(
        409,
        assertThrows(
                DomainException.class,
                () ->
                    payments.action(
                        scope.merchant,
                        payment,
                        "refund",
                        "1.00",
                        "Open dispute blocks new refund",
                        WorkflowSupport.key()))
            .status());
    assertEquals(
        1, db.scalar("select count(*) from operation where resource_id=?", Long.class, payment));
    clear();
    new org.springframework.transaction.support.TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              db.lockMerchant(scope.merchant);
              payments.applyResult(
                  scope.merchant,
                  db.one("select * from operation where id=?", refund),
                  Map.of("state", "NO_EFFECT"));
              db.exec(
                  "update operation set status='FAILED',reason='Authenticated no-effect fixture'"
                      + " where id=?",
                  refund);
              db.exec(
                  "update durable_job set state='DONE',generation=generation+1,lease_until=null"
                      + " where operation_id=?",
                  refund);
              db.exec("update system_control set unfinished_jobs=unfinished_jobs-1 where id=true");
            });
    assertEquals(
        0L,
        ((Number)
                db.one("select reserved_refund_minor from payment where id=?", payment)
                    .get("reserved_refund_minor"))
            .longValue());
    assertEquals(
        "RELEASED",
        db.one("select state from refund_reservation where operation_id=?", refund).get("state"));
  }

  @Test
  void matchingNotificationAdmissionFailureRollsBackEvidenceAndRetainsJob() throws Exception {
    Scope scope = scope();
    UUID recon = reconciler();
    matchingEvidence(scope, 1);
    as(recon);
    UUID run = matchingJobs.admit(scope.merchant);
    String name = "test_notice_" + UUID.randomUUID().toString().replace("-", "");
    try (var c = DriverManager.getConnection(URL, "mprp_owner", OWNER_PASSWORD)) {
      exec(
          c,
          "create function "
              + name
              + "() returns trigger language plpgsql as $$ begin if NEW.merchant_id='"
              + scope.merchant
              + "'::uuid then raise exception 'injected required notification admission failure';"
              + " end if; return NEW; end $$");
      exec(
          c,
          "create trigger "
              + name
              + " before insert on notification for each row execute function "
              + name
              + "()");
    }
    MatchingWorker worker = new MatchingWorker(db, matches, manager, notice, true);
    try {
      worker.discover();
      assertEquals(
          0,
          db.scalar(
              "select count(*) from allocation a join match_group g on g.id=a.group_id where"
                  + " g.merchant_id=?",
              Long.class,
              scope.merchant));
      assertEquals(
          "READY",
          db.one("select state from durable_job where kind='MATCHING' and resource_id=?", run)
              .get("state"));
    } finally {
      try (var c = DriverManager.getConnection(URL, "mprp_owner", OWNER_PASSWORD)) {
        exec(c, "drop trigger " + name + " on notification");
        exec(c, "drop function " + name + "()");
      }
    }
    db.exec(
        "update durable_job set due_at=clock_timestamp() where kind='MATCHING' and resource_id=?",
        run);
    worker.discover();
    assertEquals(
        "SUCCEEDED", db.one("select status from matching_run where id=?", run).get("status"));
    assertEquals(
        2,
        db.scalar(
            "select count(*) from allocation a join match_group g on g.id=a.group_id where"
                + " g.merchant_id=?",
            Long.class,
            scope.merchant));
    assertEquals(
        4,
        db.scalar("select count(*) from journal where merchant_id=?", Long.class, scope.merchant));
  }
}
