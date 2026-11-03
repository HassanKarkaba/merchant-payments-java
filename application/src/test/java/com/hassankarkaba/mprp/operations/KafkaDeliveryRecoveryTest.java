package com.hassankarkaba.mprp.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.time.Instant;
import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real runtime-role commits; callback and Kafka transport are controlled doubles, not broker
 * qualification.
 */
@EnabledIfEnvironmentVariable(named = "MPRP_MAIN_TEST_JDBC", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaDeliveryRecoveryTest {
  private Db db;
  private JdbcTemplate owner;
  private TransactionTemplate tx;
  private CallbackService callbacks;
  private KafkaTemplate<String, String> producer;
  private KafkaTransport transport;
  private UUID environment, run;
  private long epoch;

  @BeforeAll
  @SuppressWarnings("unchecked")
  void setup() {
    String url = System.getenv("MPRP_MAIN_TEST_JDBC");
    assertTrue(url.contains("mprp_java_verify"), "Refuse a nonverification database");
    String ownerPassword = System.getenv("MPRP_MAIN_TEST_OWNER_PASSWORD");
    Flyway.configure()
        .dataSource(url, "mprp_owner", ownerPassword)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    owner = new JdbcTemplate(new DriverManagerDataSource(url, "mprp_owner", ownerPassword));
    owner.update(
        "insert into system_control(id,environment_id,restore_hold) values(true,?,false) on"
            + " conflict do nothing",
        UUID.randomUUID());
    var control = owner.queryForMap("select * from system_control where id=true");
    environment = (UUID) control.get("environment_id");
    run = (UUID) control.get("run_id");
    epoch = ((Number) control.get("dispatch_epoch")).longValue();
    var dataSource =
        new DriverManagerDataSource(
            url, "mprp_runtime", System.getenv("MPRP_MAIN_TEST_RUNTIME_PASSWORD"));
    db = new Db(new JdbcTemplate(dataSource));
    var manager = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(manager);
    callbacks = mock(CallbackService.class);
    producer = mock(KafkaTemplate.class);
    transport =
        new KafkaTransport(
            db, producer, manager, callbacks, new Events(db), environment.toString(), true);
  }

  @BeforeEach
  void resetCallback() {
    reset(callbacks);
  }

  private Map<String, Object> envelope(
      UUID aggregate, long sequence, Map<String, Object> reference) {
    UUID id = UUID.randomUUID();
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("event_id", id.toString());
    e.put("schema_version", 1);
    e.put("policy_version", "mprp_v1");
    e.put("environment_id", environment.toString());
    e.put("run_id", run.toString());
    e.put("dispatch_epoch", epoch);
    e.put("aggregate_type", "merchant_work");
    e.put("aggregate_id", aggregate.toString());
    e.put("aggregate_version", sequence);
    e.put("stream_sequence", sequence);
    e.put("merchant_id", null);
    e.put("event_type", "TEST_COMMITTED_REFERENCE");
    e.put("occurred_at", CanonicalJson.instant(Instant.now()));
    e.put("correlation_id", id.toString());
    e.put("causation_id", null);
    e.put("reference", reference);
    e.put("payload_digest", CanonicalJson.sha256(reference));
    return e;
  }

  private UUID job(UUID resource) {
    UUID job = UUID.randomUUID();
    owner.update(
        "insert into durable_job(id,kind,resource_id,due_at)"
            + " values(?,'EXPORT',?,clock_timestamp()+interval '1 day')",
        job,
        resource);
    return job;
  }

  private long count(String sql, Object... values) {
    return db.scalar(sql, Long.class, values);
  }

  private long sequence(UUID aggregate) {
    return count(
        "select sequence from consumer_stream where consumer='worker.s1' and stream=? and"
            + " aggregate_id=?",
        transport.topic("work.v1"),
        aggregate);
  }

  private void consume(Map<String, Object> envelope) {
    Acknowledgment ack = mock(Acknowledgment.class);
    doAnswer(
            invocation -> {
              assertEquals(
                  1,
                  count(
                      "select count(*) from consumer_receipt where consumer='worker.s1' and"
                          + " event_id=?",
                      UUID.fromString(envelope.get("event_id").toString())),
                  "A separate runtime connection must observe the committed receipt before"
                      + " acknowledgment");
              return null;
            })
        .when(ack)
        .acknowledge();
    transport.consume(
        new ConsumerRecord<>(
            transport.topic("work.v1"),
            0,
            1L,
            envelope.get("aggregate_id").toString(),
            CanonicalJson.string(envelope)),
        ack);
    verify(ack).acknowledge();
    assertEquals(
        1,
        count(
            "select count(*) from consumer_receipt where consumer='worker.s1' and event_id=?",
            UUID.fromString(envelope.get("event_id").toString())));
  }

  private boolean due(UUID job) {
    return db.scalar(
        "select due_at<=clock_timestamp() from durable_job where id=?", Boolean.class, job);
  }

  @Test
  void deferredFailureCannotUndoPredecessorAndExactRedriveResumesOriginalEvent() {
    UUID aggregate = UUID.randomUUID(),
        firstResource = UUID.randomUUID(),
        secondResource = UUID.randomUUID(),
        inbox = UUID.randomUUID();
    UUID firstJob = job(firstResource), secondJob = job(secondResource);
    var second =
        envelope(
            aggregate,
            2,
            Map.of("export_id", secondResource.toString(), "inbox_id", inbox.toString()));
    UUID secondId = UUID.fromString(second.get("event_id").toString());
    doThrow(new DomainException(409, "Controlled invalid callback reference"))
        .when(callbacks)
        .apply(inbox);
    consume(second);
    assertFalse(due(secondJob));
    assertEquals(0, sequence(aggregate));
    assertEquals(
        "DEFERRED",
        db.scalar(
            "select disposition from consumer_receipt where consumer='worker.s1' and event_id=?",
            String.class,
            secondId));
    var first = envelope(aggregate, 1, Map.of("export_id", firstResource.toString()));
    consume(first);
    assertTrue(due(firstJob));
    assertEquals(1, sequence(aggregate));
    transport.drainDeferred();
    assertTrue(due(firstJob));
    assertFalse(due(secondJob));
    assertEquals(1, sequence(aggregate));
    var failure = db.one("select * from deferred_failure where event_id=?", secondId);
    assertEquals(1, count("select count(*) from deferred_event where event_id=?", secondId));
    assertEquals(
        1,
        count("select count(*) from quarantine_payload where id=?", failure.get("quarantine_id")));
    assertEquals(
        1,
        count(
            "select count(*) from publication_outbox where stream='failures.v1' and aggregate_id=?",
            failure.get("quarantine_id")));
    transport.drainDeferred();
    assertEquals(1, count("select count(*) from deferred_failure where event_id=?", secondId));
    UUID actor = UUID.randomUUID();
    assertThrows(
        DomainException.class,
        () ->
            tx.executeWithoutResult(
                s -> transport.redrive((UUID) failure.get("quarantine_id"), actor)));
    assertEquals(0, count("select count(*) from redrive_receipt where event_id=?", secondId));
    reset(callbacks);
    tx.executeWithoutResult(s -> transport.redrive((UUID) failure.get("quarantine_id"), actor));
    assertTrue(due(secondJob));
    assertEquals(2, sequence(aggregate));
    assertEquals(0, count("select count(*) from deferred_event where event_id=?", secondId));
    assertEquals(1, count("select count(*) from redrive_receipt where event_id=?", secondId));
    assertEquals(1, count("select count(*) from deferred_failure where event_id=?", secondId));
    consume(second);
    verify(callbacks, times(1)).apply(inbox);
    assertEquals(
        CanonicalJson.sha256(second),
        db.scalar(
            "select digest from consumer_receipt where consumer='worker.s1' and event_id=?",
            String.class,
            secondId));
  }

  @Test
  void malformedReferenceIsDurablyQuarantinedBeforeDeferralAndAck() {
    UUID aggregate = UUID.randomUUID();
    var poison = envelope(aggregate, 2, Map.of("operation_id", "invalid UUID"));
    consume(poison);
    UUID id = UUID.fromString(poison.get("event_id").toString());
    assertEquals(
        "QUARANTINED",
        db.scalar(
            "select disposition from consumer_receipt where consumer='worker.s1' and event_id=?",
            String.class,
            id));
    assertEquals(0, count("select count(*) from deferred_event where event_id=?", id));
    assertEquals(
        1,
        count(
            "select count(*) from quarantine where digest=?",
            CanonicalJson.digest(CanonicalJson.bytes(poison))));
    consume(envelope(aggregate, 1, Map.of()));
    assertEquals(1, sequence(aggregate));
    verifyNoInteractions(callbacks);
  }

  @Test
  void retiredRunCannotHideInGapOrLegacyDeferredQueue() {
    UUID aggregate = UUID.randomUUID(), resource = UUID.randomUUID();
    UUID job = job(resource);
    var old = envelope(aggregate, 2, Map.of("export_id", resource.toString()));
    old.put("run_id", UUID.randomUUID().toString());
    consume(old);
    UUID id = UUID.fromString(old.get("event_id").toString());
    assertEquals(
        "QUARANTINED",
        db.scalar(
            "select disposition from consumer_receipt where consumer='worker.s1' and event_id=?",
            String.class,
            id));
    assertEquals(0, count("select count(*) from deferred_event where event_id=?", id));
    // A pre-fix retained deferred row is deliberately seeded to exercise upgrade recovery.
    UUID legacyAggregate = UUID.randomUUID();
    var legacy = envelope(legacyAggregate, 2, Map.of("export_id", resource.toString()));
    legacy.put("run_id", UUID.randomUUID().toString());
    UUID legacyId = UUID.fromString(legacy.get("event_id").toString());
    String stream = transport.topic("work.v1");
    owner.update(
        "insert into consumer_stream(consumer,stream,aggregate_id,sequence)"
            + " values('worker.s1',?,?,1)",
        stream,
        legacyAggregate);
    owner.update(
        "insert into consumer_receipt(consumer,event_id,digest,disposition)"
            + " values('worker.s1',?,?,'DEFERRED')",
        legacyId,
        CanonicalJson.sha256(legacy));
    owner.update(
        "insert into deferred_event(consumer,event_id,envelope) values(?,?,?)",
        "worker.s1:" + stream,
        legacyId,
        CanonicalJson.string(legacy));
    transport.drainDeferred();
    assertFalse(due(job));
    assertEquals(1, sequence(legacyAggregate));
    assertEquals(1, count("select count(*) from deferred_failure where event_id=?", legacyId));
  }

  @Test
  void publishedRetainedDatabaseReferenceSurvivesBrokerDeliveryLossAndRetiredRun() {
    UUID aggregate = UUID.randomUUID(), resource = UUID.randomUUID();
    UUID job = job(resource);
    var e = envelope(aggregate, 1, Map.of("export_id", resource.toString()));
    e.put("run_id", UUID.randomUUID().toString());
    UUID id = UUID.fromString(e.get("event_id").toString());
    owner.update(
        "insert into"
            + " publication_outbox(id,aggregate_id,stream,sequence,envelope,digest,published_at,created_at)"
            + " values(?,?,'work.v1',1,?,?,clock_timestamp()-interval '1"
            + " hour',clock_timestamp()-interval '1 hour')",
        id,
        aggregate,
        CanonicalJson.string(e),
        CanonicalJson.sha256(e));
    transport.discoverRetainedDeliveries();
    assertTrue(due(job));
    assertEquals(1, sequence(aggregate));
    assertNotNull(
        db.one("select consumed_at from publication_outbox where id=?", id).get("consumed_at"));
    assertEquals(
        "APPLIED",
        db.scalar(
            "select disposition from consumer_receipt where consumer='worker.s1' and event_id=?",
            String.class,
            id));
    transport.discoverRetainedDeliveries();
    consume(e);
    assertEquals(
        1,
        count(
            "select count(*) from consumer_receipt where consumer='worker.s1' and event_id=?", id));
    verifyNoInteractions(producer);
  }

  @Test
  void dispositionRequiresMatchingImmutableReceiptAndIdentityConflictCannotReplaceIt() {
    UUID forgedAggregate = UUID.randomUUID();
    var forged = envelope(forgedAggregate, 1, Map.of());
    UUID forgedId = UUID.fromString(forged.get("event_id").toString());
    assertThrows(
        DataAccessException.class,
        () ->
            tx.executeWithoutResult(
                s ->
                    db.exec(
                        "insert into"
                            + " publication_outbox(id,aggregate_id,stream,sequence,envelope,digest,consumed_at)"
                            + " values(?,?,'work.v1',1,?,?,clock_timestamp())",
                        forgedId,
                        forgedAggregate,
                        CanonicalJson.string(forged),
                        CanonicalJson.sha256(forged))));
    assertEquals(0, count("select count(*) from publication_outbox where id=?", forgedId));
    UUID aggregate = UUID.randomUUID();
    var e = envelope(aggregate, 1, Map.of());
    UUID id = UUID.fromString(e.get("event_id").toString());
    String hash = CanonicalJson.sha256(e);
    owner.update(
        "insert into publication_outbox(id,aggregate_id,stream,sequence,envelope,digest)"
            + " values(?,?,'work.v1',1,?,?)",
        id,
        aggregate,
        CanonicalJson.string(e),
        hash);
    assertThrows(
        DataAccessException.class,
        () ->
            tx.executeWithoutResult(
                s ->
                    db.exec(
                        "update publication_outbox set consumed_at=clock_timestamp() where id=?",
                        id)));
    assertNull(
        db.one("select consumed_at from publication_outbox where id=?", id).get("consumed_at"));
    consume(e);
    Object consumed =
        db.one("select consumed_at from publication_outbox where id=?", id).get("consumed_at");
    assertNotNull(consumed);
    assertThrows(
        DataAccessException.class,
        () ->
            tx.executeWithoutResult(
                s -> db.exec("update publication_outbox set consumed_at=null where id=?", id)));
    var conflict = new LinkedHashMap<>(e);
    conflict.put("event_type", "DIFFERENT_CONTENT");
    consume(conflict);
    assertEquals(
        hash,
        db.scalar(
            "select digest from consumer_receipt where consumer='worker.s1' and event_id=?",
            String.class,
            id));
    assertEquals(
        consumed,
        db.one("select consumed_at from publication_outbox where id=?", id).get("consumed_at"));
    assertEquals(
        1,
        count(
            "select count(*) from quarantine where digest=?",
            CanonicalJson.digest(CanonicalJson.bytes(conflict))));
  }

  @Test
  void transientApplicationFailureRetainsOffsetAndHasNoFalseReceiptOrQuarantine() {
    UUID aggregate = UUID.randomUUID(), inbox = UUID.randomUUID();
    var e = envelope(aggregate, 1, Map.of("inbox_id", inbox.toString()));
    UUID id = UUID.fromString(e.get("event_id").toString());
    doThrow(new DataAccessResourceFailureException("Controlled transient database outage"))
        .when(callbacks)
        .apply(inbox);
    Acknowledgment ack = mock(Acknowledgment.class);
    var record =
        new ConsumerRecord<String, String>(
            transport.topic("work.v1"), 0, 1L, aggregate.toString(), CanonicalJson.string(e));
    assertThrows(DataAccessResourceFailureException.class, () -> transport.consume(record, ack));
    verifyNoInteractions(ack);
    assertEquals(0, count("select count(*) from consumer_receipt where event_id=?", id));
    assertEquals(
        0,
        count(
            "select count(*) from quarantine where digest=?",
            CanonicalJson.digest(CanonicalJson.bytes(e))));
    reset(callbacks);
    consume(e);
    assertEquals(1, sequence(aggregate));
  }

  @Test
  void failureReferencesAreDisposedWithoutReapplyingTheFailedCallbackOrJob() {
    UUID aggregate = UUID.randomUUID(), inbox = UUID.randomUUID(), resource = UUID.randomUUID();
    UUID job = job(resource);
    var failure =
        envelope(
            aggregate, 1, Map.of("inbox_id", inbox.toString(), "export_id", resource.toString()));
    failure.put("event_type", "PROVIDER_HISTORY_REQUIRES_ATTENTION");
    UUID id = UUID.fromString(failure.get("event_id").toString());
    Acknowledgment ack = mock(Acknowledgment.class);
    transport.consume(
        new ConsumerRecord<>(
            transport.topic("failures.v1"),
            0,
            1L,
            aggregate.toString(),
            CanonicalJson.string(failure)),
        ack);
    verify(ack).acknowledge();
    verifyNoInteractions(callbacks);
    assertFalse(due(job));
    assertEquals(
        "APPLIED",
        db.scalar(
            "select disposition from consumer_receipt where consumer='worker.s1' and event_id=?",
            String.class,
            id));
    assertEquals(
        1,
        count(
            "select sequence from consumer_stream where consumer='worker.s1' and stream=? and"
                + " aggregate_id=?",
            transport.topic("failures.v1"),
            aggregate));
  }

  @Test
  void tombstoneAndOversizePoisonRecordsReceiveDurableDispositionWithoutRetainedPayload() {
    long before = count("select count(*) from quarantine where kind='KAFKA'");
    for (String raw : Arrays.asList(null, "a".repeat(262145))) {
      Acknowledgment ack = mock(Acknowledgment.class);
      doAnswer(
              invocation -> {
                assertTrue(count("select count(*) from quarantine where kind='KAFKA'") > before);
                return null;
              })
          .when(ack)
          .acknowledge();
      transport.consume(
          new ConsumerRecord<String, String>(
              transport.topic("work.v1"), 0, 1L, UUID.randomUUID().toString(), raw),
          ack);
      verify(ack).acknowledge();
      String hash =
          CanonicalJson.digest(
              raw == null ? new byte[0] : raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertEquals(
          1,
          count(
              "select count(*) from quarantine q join publication_outbox p on p.aggregate_id=q.id"
                  + " and p.stream='failures.v1' where q.kind='KAFKA' and q.digest=?",
              hash));
      assertEquals(
          0,
          count(
              "select count(*) from quarantine q join quarantine_payload p on p.id=q.id where"
                  + " q.kind='KAFKA' and q.digest=?",
              hash));
    }
    assertEquals(before + 2, count("select count(*) from quarantine where kind='KAFKA'"));
    verifyNoInteractions(callbacks);
  }
}
