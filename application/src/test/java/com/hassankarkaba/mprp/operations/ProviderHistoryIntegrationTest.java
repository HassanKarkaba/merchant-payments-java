package com.hassankarkaba.mprp.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hassankarkaba.mprp.accounting.LedgerSafety;
import com.hassankarkaba.mprp.accounting.LedgerService;
import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.net.URI;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real PostgreSQL/runtime-role staging, commit and lease checks. Provider transport and final
 * callback are controlled doubles: this suite does not qualify HTTP, Kafka or composed callbacks.
 */
@EnabledIfEnvironmentVariable(named = "MPRP_MAIN_TEST_JDBC", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderHistoryIntegrationTest {
  private String url, ownerPassword, runtimePassword;
  private UUID environment, providerInstance, run;
  private long epoch;
  private AnnotationConfigApplicationContext context;
  private JdbcTemplate owner;
  private Db db;
  private ProviderClient provider;
  private CallbackService callback;
  private ProviderHistoryService histories;
  private LedgerService ledger;
  private TransactionTemplate tx;

  @BeforeAll
  void setup() {
    url = System.getenv("MPRP_MAIN_TEST_JDBC");
    ownerPassword = System.getenv("MPRP_MAIN_TEST_OWNER_PASSWORD");
    runtimePassword = System.getenv("MPRP_MAIN_TEST_RUNTIME_PASSWORD");
    assertTrue(url.contains("mprp_java_verify"), "Refusing a nonverification database");
    Flyway.configure()
        .dataSource(url, "mprp_owner", ownerPassword)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    owner = new JdbcTemplate(new DriverManagerDataSource(url, "mprp_owner", ownerPassword));
    owner.update(
        "insert into system_control(id,environment_id,provider_instance_id,restore_hold) values"
            + " (true,?,?,false) on conflict do nothing",
        UUID.randomUUID(),
        UUID.randomUUID());
    var control = owner.queryForMap("select * from system_control where id=true");
    if (control.get("provider_instance_id") == null) {
      providerInstance = UUID.randomUUID();
      owner.update(
          "update system_control set provider_instance_id=? where id=true", providerInstance);
    } else providerInstance = (UUID) control.get("provider_instance_id");
    environment = (UUID) control.get("environment_id");
    run = (UUID) control.get("run_id");
    epoch = ((Number) control.get("dispatch_epoch")).longValue();
    var dataSource = new DriverManagerDataSource(url, "mprp_runtime", runtimePassword);
    provider = mock(ProviderClient.class);
    callback = mock(CallbackService.class);
    context = new AnnotationConfigApplicationContext();
    context.registerBean("dataSource", javax.sql.DataSource.class, () -> dataSource);
    context.registerBean(
        "transactionManager",
        org.springframework.transaction.PlatformTransactionManager.class,
        () -> new DataSourceTransactionManager(dataSource));
    context.registerBean(Db.class, () -> new Db(new JdbcTemplate(dataSource)));
    context.registerBean(ProviderClient.class, () -> provider);
    context.registerBean(CallbackService.class, () -> callback);
    context.registerBean(Authz.class, () -> mock(Authz.class));
    context.registerBean(AuditService.class);
    context.registerBean(LedgerSafety.class);
    context.registerBean(LedgerService.class);
    context.registerBean(ProviderResultValidator.class);
    context.registerBean(ProviderHistoryService.class);
    context.registerBean(Events.class);
    context.register(TxConfiguration.class);
    context.refresh();
    db = context.getBean(Db.class);
    histories = context.getBean(ProviderHistoryService.class);
    ledger = context.getBean(LedgerService.class);
    tx =
        new TransactionTemplate(
            context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
  }

  @AfterAll
  void close() {
    if (context != null) context.close();
  }

  @BeforeEach
  void resetDoubles() {
    reset(provider, callback);
  }

  @org.springframework.context.annotation.Configuration
  @EnableTransactionManagement
  static class TxConfiguration {}

  private record Fixture(
      UUID merchant,
      UUID operation,
      UUID job,
      UUID resource,
      UUID batch,
      List<Map<String, Object>> sources,
      Map<String, Object> summary,
      Map<String, Object> claim) {}

  private Map<String, Object> map(Object... pairs) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i].toString(), pairs[i + 1]);
    return out;
  }

  private Fixture fixture(int economic, boolean captures) throws Exception {
    UUID merchant = UUID.randomUUID(),
        actor = UUID.randomUUID(),
        operation = UUID.randomUUID(),
        job = UUID.randomUUID(),
        resource = UUID.randomUUID(),
        batch = UUID.randomUUID(),
        jobOwner = UUID.randomUUID();
    String request = CanonicalJson.string(Map.of("cutoff_identity", "history-verification")),
        fingerprint = CanonicalJson.sha256(CanonicalJson.parse(request));
    try (var c = DriverManager.getConnection(url, "mprp_owner", ownerPassword)) {
      c.setAutoCommit(false);
      exec(
          c,
          "insert into app_user(id,email,verified,totp_confirmed,platform_role) values"
              + " (?,?,true,true,'OPERATIONS')",
          actor,
          actor + "@demo.invalid");
      exec(
          c,
          "insert into merchant(id,name,status,provider_account_id,bank_account_id) values"
              + " (?,'Isolated provider history','ACTIVE',?,?)",
          merchant,
          merchant,
          merchant);
      exec(
          c,
          "insert into"
              + " operation(id,merchant_id,actor_id,kind,resource_id,action_key,fingerprint,provider_key,payload,status)"
              + " values (?,?,?,'flush',?,?,?,?,?,'DISPATCHING')",
          operation,
          merchant,
          actor,
          resource,
          "a".repeat(32),
          fingerprint,
          "history_" + operation,
          request);
      c.commit();
    }
    var sources = new ArrayList<Map<String, Object>>();
    String occurred =
        CanonicalJson.instant(Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS));
    long net = 0;
    if (captures) {
      try (var c = DriverManager.getConnection(url, "mprp_owner", ownerPassword)) {
        c.setAutoCommit(false);
        try (var payments =
                c.prepareStatement(
                    "insert into"
                        + " payment(id,merchant_id,status,capture_mode,authorized_minor,customer_name,authorization_expires_at)"
                        + " values (?,?,'AUTHORIZED','MANUAL',10000,'Fictional history"
                        + " customer',clock_timestamp()+interval '1 day')");
            var admissions =
                c.prepareStatement(
                    "insert into"
                        + " operation(id,merchant_id,actor_id,kind,resource_id,action_key,fingerprint,provider_key,payload,status)"
                        + " values (?,?,?,'capture',?,?,?,?,?,'UNKNOWN')")) {
          for (int i = 0; i < economic; i++) {
            UUID payment = UUID.randomUUID(), captureOp = UUID.randomUUID();
            String payload = "{\"amount_minor\":10000}";
            payments.setObject(1, payment);
            payments.setObject(2, merchant);
            payments.addBatch();
            Object[] values = {
              captureOp,
              merchant,
              actor,
              payment,
              "b".repeat(32),
              CanonicalJson.sha256(CanonicalJson.parse(payload)),
              "capture_" + captureOp,
              payload
            };
            for (int n = 0; n < values.length; n++) admissions.setObject(n + 1, values[n]);
            admissions.addBatch();
            sources.add(
                source(
                    merchant,
                    payment,
                    batch,
                    "CAPTURE",
                    10000,
                    sources.size() + 1L,
                    occurred,
                    true));
            sources.add(
                source(merchant, payment, batch, "FEE", -320, sources.size() + 1L, occurred, true));
            net += 9680;
          }
          payments.executeBatch();
          admissions.executeBatch();
        }
        c.commit();
      }
    } else
      for (int i = 0; i < economic; i++) {
        sources.add(
            source(
                merchant,
                UUID.randomUUID(),
                batch,
                "REFUND",
                -1,
                sources.size() + 1L,
                occurred,
                true));
        net--;
      }
    var transfer =
        source(
            merchant,
            batch,
            batch,
            net == 0 ? "BATCH_ZERO" : "SETTLEMENT",
            net,
            sources.size() + 1L,
            occurred,
            false);
    sources.add(transfer);
    var metadata =
        map(
            "batch_id",
            batch.toString(),
            "net_minor",
            net,
            "amount_minor",
            net,
            "cutoff",
            "history-verification",
            "occurred_at",
            occurred,
            "transfer_reference",
            net == 0 ? "" : transfer.get("source_id"),
            "state",
            "SUCCEEDED",
            "business_state",
            "COMPLETED",
            "batch_zero",
            net == 0);
    var summary =
        map(
            "schema_version",
            1,
            "policy_version",
            "mprp_v1",
            "environment_id",
            environment.toString(),
            "simulator_instance_id",
            providerInstance.toString(),
            "provider_account_id",
            merchant.toString(),
            "currency",
            "USD",
            "operation_key",
            "history_" + operation,
            "payload_hash",
            fingerprint,
            "resource_id",
            resource.toString(),
            "state",
            "SUCCEEDED",
            "business_state",
            "COMPLETED",
            "batch_id",
            batch.toString(),
            "batches",
            List.of(metadata),
            "sources",
            List.of(),
            "history_required",
            true,
            "history_resource_id",
            batch.toString(),
            "source_high_water",
            (long) sources.size(),
            "source_count",
            (long) sources.size(),
            "history_source_count",
            (long) sources.size());
    // Begin the bounded lease after fixture data preparation, even on a loaded verification VM.
    try (var c = DriverManager.getConnection(url, "mprp_owner", ownerPassword)) {
      c.setAutoCommit(false);
      exec(
          c,
          "insert into"
              + " durable_job(id,operation_id,kind,resource_id,merchant_id,state,owner,generation,run_id,dispatch_epoch,lease_until)"
              + " values (?,?,'OPERATION',?,?,'RUNNING',?,1,?,?,clock_timestamp()+interval '30"
              + " seconds')",
          job,
          operation,
          resource,
          merchant,
          jobOwner,
          run,
          epoch);
      exec(c, "update system_control set unfinished_jobs=unfinished_jobs+1 where id=true");
      c.commit();
    }
    Map<String, Object> claim =
        new HashMap<>(db.one("select * from operation where id=?", operation));
    claim.putAll(
        map("job_id", job, "owner", jobOwner, "generation", 1L, "run_id", run, "epoch", epoch));
    return new Fixture(merchant, operation, job, resource, batch, sources, summary, claim);
  }

  private Map<String, Object> source(
      UUID merchant,
      UUID resource,
      UUID batch,
      String type,
      long amount,
      long sequence,
      String occurred,
      boolean clearing) {
    UUID id = UUID.randomUUID();
    return map(
        "source_id",
        id.toString(),
        "sequence",
        sequence,
        "provider_account_id",
        merchant.toString(),
        "resource_id",
        resource.toString(),
        "payment_id",
        clearing ? resource.toString() : null,
        "event_type",
        type,
        "amount_minor",
        amount,
        "currency",
        "USD",
        "occurred_at",
        occurred,
        "reference",
        id.toString(),
        "batch_id",
        batch.toString(),
        "clearing_line",
        clearing);
  }

  private void exec(Connection c, String sql, Object... values) throws SQLException {
    try (var q = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]);
      q.executeUpdate();
    }
  }

  private AtomicInteger pages(Fixture fixture, LongFunction<List<Map<String, Object>>> rows) {
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              String path = invocation.getArgument(0);
              assertTrue(path.startsWith("/v1/history/" + fixture.batch + "/?after="));
              var query = URI.create("http://local" + path).getQuery();
              long after = -1, snapshot = -1;
              for (String part : query.split("&")) {
                String[] pair = part.split("=");
                if (pair[0].equals("after")) after = Long.parseLong(pair[1]);
                if (pair[0].equals("snapshot")) snapshot = Long.parseLong(pair[1]);
              }
              assertEquals(fixture.sources.size(), snapshot);
              List<Map<String, Object>> page = rows.apply(after);
              calls.incrementAndGet();
              return map(
                  "schema_version",
                  1,
                  "policy_version",
                  "mprp_v1",
                  "snapshot",
                  snapshot,
                  "source_high_water",
                  snapshot,
                  "after",
                  after,
                  "next_after",
                  page.isEmpty() ? after : page.getLast().get("sequence"),
                  "complete",
                  page.size() < 250,
                  "sources",
                  page);
            })
        .when(provider)
        .get(anyString());
    return calls;
  }

  private AtomicInteger pages(Fixture fixture) {
    return pages(
        fixture,
        after ->
            fixture.sources.stream()
                .filter(row -> ((Number) row.get("sequence")).longValue() > after)
                .limit(250)
                .toList());
  }

  private Map<String, Object> reclaim(Fixture fixture) {
    owner.update(
        "update durable_job set state='READY',generation=generation+1,lease_until=null where id=?",
        fixture.job);
    UUID next = UUID.randomUUID();
    owner.update(
        "update durable_job set"
            + " state='RUNNING',owner=?,generation=generation+1,run_id=?,dispatch_epoch=?,lease_until=clock_timestamp()+interval"
            + " '30 seconds' where id=?",
        next,
        run,
        epoch,
        fixture.job);
    var row = owner.queryForMap("select owner,generation from durable_job where id=?", fixture.job);
    var claim = new HashMap<>(fixture.claim);
    claim.putAll(row);
    return claim;
  }

  private long count(String sql, Object... values) {
    return db.scalar(sql, Long.class, values);
  }

  @Test
  void fixedSnapshotPagesResumeUnderNewOwnerAndStaleOwnerCannotAdvance() throws Exception {
    Fixture f = fixture(500, false);
    AtomicInteger calls = pages(f);
    assertTrue(histories.expand(f.claim, f.summary).isEmpty());
    assertEquals(
        250,
        count("select source_count from provider_history_task where operation_id=?", f.operation));
    Map<String, Object> newClaim = reclaim(f);
    assertTrue(histories.expand(f.claim, f.summary).isEmpty());
    assertEquals(1, calls.get());
    assertTrue(histories.expand(newClaim, f.summary).isEmpty());
    assertEquals(
        500,
        count("select source_count from provider_history_task where operation_id=?", f.operation));
    var complete = histories.expand(newClaim, f.summary).orElseThrow();
    assertEquals(501, ((List<?>) complete.get("sources")).size());
    assertEquals(3, calls.get());
    assertEquals(0, count("select count(*) from journal where merchant_id=?", f.merchant));
    assertEquals(0, count("select count(*) from economic_source where merchant_id=?", f.merchant));
    assertEquals(
        501,
        ((List<?>) histories.completed(f.operation, f.summary).orElseThrow().get("sources"))
            .size());
    verify(provider, never()).call(anyString(), anyMap());
  }

  @Test
  void wrongMerchantClaimAndTransactionWrappingCannotStageOrCallProvider() throws Exception {
    Fixture f = fixture(0, false);
    pages(f);
    UUID other = UUID.randomUUID();
    owner.update(
        "insert into merchant(id,name,provider_account_id,bank_account_id) values (?,'Other"
            + " fixture',?,?)",
        other,
        other,
        other);
    var bad = new HashMap<>(f.claim);
    bad.put("merchant_id", other);
    var summary = new HashMap<>(f.summary);
    summary.put("provider_account_id", other.toString());
    assertTrue(histories.expand(bad, summary).isEmpty());
    assertEquals(
        0, count("select count(*) from provider_history_task where operation_id=?", f.operation));
    tx.executeWithoutResult(
        s ->
            assertEquals(
                409,
                assertThrows(DomainException.class, () -> histories.expand(f.claim, f.summary))
                    .status()));
    verify(provider, never()).get(anyString());
  }

  @Test
  void providerPageReturningAfterReclaimCannotAdvanceTheOldClaim() throws Exception {
    Fixture f = fixture(0, false);
    AtomicReference<Map<String, Object>> next = new AtomicReference<>();
    pages(
        f,
        after -> {
          next.set(reclaim(f));
          return f.sources;
        });
    assertTrue(histories.expand(f.claim, f.summary).isEmpty());
    assertEquals(
        0,
        count("select source_count from provider_history_task where operation_id=?", f.operation));
    assertEquals(
        0, count("select cursor from provider_history_task where operation_id=?", f.operation));
    pages(f);
    assertEquals(
        1, ((List<?>) histories.expand(next.get(), f.summary).orElseThrow().get("sources")).size());
    verify(provider, times(2)).get(anyString());
  }

  @Test
  void declaredCountAndImmutableSnapshotProofMustBothMatchBeforePosting() throws Exception {
    Fixture shortPage = fixture(0, false);
    pages(shortPage);
    var wrongCount = new HashMap<>(shortPage.summary);
    wrongCount.put("source_count", 2L);
    wrongCount.put("history_source_count", 2L);
    assertTrue(histories.expand(shortPage.claim, wrongCount).isEmpty());
    assertTrue(histories.manual(shortPage.operation));
    assertEquals(
        0, count("select count(*) from economic_source where merchant_id=?", shortPage.merchant));
    Fixture f = fixture(0, false);
    pages(f);
    var full = histories.expand(f.claim, f.summary).orElseThrow();
    var changed = new LinkedHashMap<>(full);
    var source = new LinkedHashMap<>(f.sources.getFirst());
    source.put("reference", UUID.randomUUID().toString());
    changed.put("sources", List.of(source));
    assertEquals(
        409,
        assertThrows(DomainException.class, () -> histories.postChunk(f.claim, changed, ledger))
            .status());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.exec(
                "update provider_history_source set source_json=source_json where task_id=(select"
                    + " task_id from provider_history_task where operation_id=?)",
                f.operation));
    assertEquals(
        1,
        ((List<?>) histories.completed(f.operation, f.summary).orElseThrow().get("sources"))
            .size());
    assertEquals(0, count("select count(*) from economic_source where merchant_id=?", f.merchant));
  }

  @Test
  void changedSourceIdentityIsQuarantinedWithoutPostingMoney() throws Exception {
    Fixture f = fixture(250, false);
    var conflicting = new HashMap<>(f.sources.getLast());
    conflicting.put("source_id", f.sources.getFirst().get("source_id"));
    conflicting.put("event_type", f.sources.getFirst().get("event_type"));
    pages(f, after -> after == 0 ? f.sources.subList(0, 250) : List.of(conflicting));
    assertTrue(histories.expand(f.claim, f.summary).isEmpty());
    assertTrue(histories.expand(f.claim, f.summary).isEmpty());
    assertTrue(histories.manual(f.operation));
    assertEquals(
        250,
        count("select source_count from provider_history_task where operation_id=?", f.operation));
    assertEquals(
        250, count("select cursor from provider_history_task where operation_id=?", f.operation));
    assertEquals(0, count("select count(*) from economic_source where merchant_id=?", f.merchant));
  }

  @Test
  void validatedJournalsCommitInBoundedChunksAndReplayDoesNotDuplicate() throws Exception {
    Fixture f = fixture(51, true);
    pages(f);
    Map<String, Object> claim = f.claim;
    var full = histories.expand(claim, f.summary).orElseThrow();
    assertFalse(histories.postChunk(claim, full, ledger));
    long posted =
        count("select posted_count from provider_history_task where operation_id=?", f.operation);
    assertTrue(posted > 0 && posted <= 100);
    assertEquals(posted, count("select count(*) from journal where merchant_id=?", f.merchant));
    assertEquals(
        0,
        count(
            "select count(*) from economic_source where merchant_id=? and event_type='SETTLEMENT'",
            f.merchant));
    boolean done = false;
    for (int turn = 0; turn < 102 && !done; turn++) {
      claim = reclaim(f);
      long before =
          count("select posted_count from provider_history_task where operation_id=?", f.operation);
      done = histories.postChunk(claim, full, ledger);
      long after =
          count("select posted_count from provider_history_task where operation_id=?", f.operation);
      assertTrue(after > before && after - before <= 100);
    }
    assertTrue(done);
    assertEquals(102, count("select count(*) from journal where merchant_id=?", f.merchant));
    assertEquals(
        493680,
        count(
            "select coalesce(sum(credit::numeric-debit::numeric),0) from ledger_entry where"
                + " merchant_id=? and account='merchant_payable'",
            f.merchant));
    claim = reclaim(f);
    assertTrue(histories.postChunk(claim, full, ledger));
    assertEquals(102, count("select count(*) from journal where merchant_id=?", f.merchant));
    assertTrue(histories.preposted(f.operation));
  }

  @Test
  void leaseExpiryAtRealCommitRollsBackHistoryProgress() throws Exception {
    Fixture f = fixture(0, false);
    pages(f);
    histories.expand(f.claim, f.summary).orElseThrow();
    String before =
        db.scalar(
            "select safe_reason from provider_history_task where operation_id=?",
            String.class,
            f.operation);
    owner.update(
        "update durable_job set lease_until=clock_timestamp()+interval '2 seconds' where id=?",
        f.job);
    try (var c = DriverManager.getConnection(url, "mprp_runtime", runtimePassword)) {
      c.setAutoCommit(false);
      exec(
          c,
          "update provider_history_task set safe_reason='Must roll back at lease expiry' where"
              + " operation_id=?",
          f.operation);
      exec(c, "update durable_job set last_error='Commit window under test' where id=?", f.job);
      try (var q = c.prepareStatement("select pg_sleep(2.1)")) {
        q.execute();
      }
      SQLException failure = assertThrows(SQLException.class, c::commit);
      assertEquals("23514", failure.getSQLState());
      c.rollback();
    }
    assertEquals(
        before,
        db.scalar(
            "select safe_reason from provider_history_task where operation_id=?",
            String.class,
            f.operation));
  }

  private UUID inbox(Fixture fixture, boolean retainedOperation) {
    if (retainedOperation) {
      owner.update("update operation set status='SUCCEEDED' where id=?", fixture.operation);
      owner.update(
          "update durable_job set state='DONE',generation=generation+1,lease_until=null where id=?",
          fixture.job);
      owner.update("update system_control set unfinished_jobs=unfinished_jobs-1 where id=true");
    }
    UUID inbox = UUID.randomUUID();
    var result = new HashMap<>(fixture.summary);
    if (!retainedOperation) {
      result.put("operation_key", "system_daily_" + fixture.batch);
      result.put("payload_hash", "");
    }
    var event =
        map(
            "event_id",
            inbox.toString(),
            "schema_version",
            1,
            "policy_version",
            "mprp_v1",
            "environment_id",
            environment.toString(),
            "simulator_instance_id",
            providerInstance.toString(),
            "provider_account_id",
            fixture.merchant.toString(),
            "event_type",
            retainedOperation ? "operation_completed" : "settlement_completed",
            "result",
            result);
    owner.update(
        "insert into callback_inbox(id,digest,payload) values (?,?,?)",
        inbox,
        CanonicalJson.sha256(event),
        CanonicalJson.string(event));
    return inbox;
  }

  @Test
  void systemAndKnownSucceededInboxesHydrateWithoutResolvingSyntheticKeys() throws Exception {
    for (boolean retained : new boolean[] {false, true}) {
      Fixture f = fixture(0, false);
      UUID inbox = inbox(f, retained);
      pages(f);
      doAnswer(
              invocation -> {
                UUID id = invocation.getArgument(0);
                assertTrue(histories.prepostedInbox(id));
                owner.update("update callback_inbox set state='APPLIED' where id=?", id);
                return null;
              })
          .when(callback)
          .apply(inbox);
      long unfinished = count("select unfinished_jobs from system_control where id=true");
      var worker =
          new SourceHistoryWorker(
              db,
              context.getBean(org.springframework.transaction.PlatformTransactionManager.class),
              histories,
              ledger,
              callback,
              context.getBean(Events.class),
              true);
      worker.discover();
      assertEquals(
          "APPLIED", db.scalar("select state from callback_inbox where id=?", String.class, inbox));
      assertEquals(
          "DONE",
          db.scalar(
              "select state from durable_job where kind='PROVIDER_HISTORY' and resource_id=?",
              String.class,
              inbox));
      assertEquals(unfinished, count("select unfinished_jobs from system_control where id=true"));
      verify(callback).apply(inbox);
    }
    verify(provider, never()).call(anyString(), anyMap());
  }

  @Test
  void callbackHandoffFailurePersistsBackoffAndEventuallyManualAttention() throws Exception {
    Fixture f = fixture(0, false);
    UUID inbox = inbox(f, false);
    pages(f);
    doThrow(new DomainException(409, "Controlled callback failure")).when(callback).apply(inbox);
    var worker =
        new SourceHistoryWorker(
            db,
            context.getBean(org.springframework.transaction.PlatformTransactionManager.class),
            histories,
            ledger,
            callback,
            context.getBean(Events.class),
            true);
    worker.discover();
    assertEquals(
        "READY",
        db.scalar(
            "select state from durable_job where kind='PROVIDER_HISTORY' and resource_id=?",
            String.class,
            inbox));
    assertTrue(
        db.scalar(
            "select due_at>clock_timestamp() from durable_job where kind='PROVIDER_HISTORY' and"
                + " resource_id=?",
            Boolean.class,
            inbox));
    assertEquals(
        "ACCEPTED", db.scalar("select state from callback_inbox where id=?", String.class, inbox));
    // Job creation time is immutable. Retain this retry and create a separate aged
    // accepted obligation instead of weakening the real identity guard to age it.
    owner.update(
        "update durable_job set due_at=clock_timestamp()+interval '1 day' where"
            + " kind='PROVIDER_HISTORY' and resource_id=?",
        inbox);
    Fixture aged = fixture(0, false);
    UUID agedInbox = inbox(aged, false);
    pages(aged);
    doThrow(new DomainException(409, "Controlled aged callback failure"))
        .when(callback)
        .apply(agedInbox);
    owner.update(
        "insert into durable_job(id,kind,resource_id,merchant_id,created_at) values"
            + " (?,'PROVIDER_HISTORY',?,?,clock_timestamp()-interval '25 hours')",
        UUID.randomUUID(),
        agedInbox,
        aged.merchant);
    owner.update("update system_control set unfinished_jobs=unfinished_jobs+1 where id=true");
    worker.discover();
    assertEquals(
        "MANUAL",
        db.scalar(
            "select state from durable_job where kind='PROVIDER_HISTORY' and resource_id=?",
            String.class,
            agedInbox));
    verify(callback).apply(inbox);
    verify(callback).apply(agedInbox);
    verify(provider, never()).call(anyString(), anyMap());
  }
}
