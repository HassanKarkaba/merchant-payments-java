package com.hassankarkaba.mprp.operations;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component("kafkaTransport")
public class KafkaTransport {
  private final Db db;
  private final KafkaTemplate<String, String> kafka;
  private final TransactionTemplate tx;
  private final CallbackService callbacks;
  private final Events events;
  private final boolean enabled;
  private final String prefix;
  private final UUID owner = UUID.randomUUID();

  public KafkaTransport(
      Db db,
      KafkaTemplate<String, String> kafka,
      org.springframework.transaction.PlatformTransactionManager manager,
      CallbackService callbacks,
      Events events,
      @Value("${mprp.environment-id}") String env,
      @Value("${mprp.worker:false}") boolean enabled) {
    this.db = db;
    this.kafka = kafka;
    this.tx = new TransactionTemplate(manager);
    this.callbacks = callbacks;
    this.events = events;
    this.enabled = enabled;
    this.prefix = "mprp." + UUID.fromString(env) + ".s1.";
  }

  public String topic(String suffix) {
    return prefix + suffix;
  }

  public String group() {
    return prefix + "workers";
  }

  @Bean
  public NewTopic workTopic() {
    return make("work.v1");
  }

  @Bean
  public NewTopic providerTopic() {
    return make("provider-events.v1");
  }

  @Bean
  public NewTopic domainTopic() {
    return make("domain-events.v1");
  }

  @Bean
  public NewTopic failuresTopic() {
    return make("failures.v1");
  }

  private NewTopic make(String n) {
    return TopicBuilder.name(topic(n))
        .partitions(3)
        .replicas(1)
        .config("retention.ms", "604800000")
        .config("max.message.bytes", "262144")
        .config("min.insync.replicas", "1")
        .build();
  }

  @Scheduled(fixedDelay = 1000)
  public void publish() {
    if (!enabled) return;
    for (int n = 0; n < 20; n++) {
      Map<String, Object> row =
          tx.execute(
              s -> {
                var control = db.one("select * from system_control where id=true for update");
                var maybe =
                    db.maybe(
                        "select o.* from publication_outbox o where published_at is null and"
                            + " (lease_until is null or lease_until<clock_timestamp()) and not"
                            + " exists(select 1 from publication_outbox p where p.stream=o.stream"
                            + " and p.aggregate_id=o.aggregate_id and p.sequence<o.sequence and"
                            + " p.published_at is null) order by created_at limit 1 for update skip"
                            + " locked");
                if (maybe.isEmpty()) return null;
                var r = maybe.get();
                long gen = Db.number(r, "generation") + 1;
                db.exec(
                    "update publication_outbox set"
                        + " owner=?,generation=?,lease_until=clock_timestamp()+interval '30"
                        + " seconds',run_id=?,dispatch_epoch=? where id=?",
                    owner,
                    gen,
                    control.get("run_id"),
                    control.get("dispatch_epoch"),
                    r.get("id"));
                r.put("generation", gen);
                return r;
              });
      if (row == null) return;
      try {
        kafka
            .send(
                topic(row.get("stream").toString()),
                row.get("aggregate_id").toString(),
                row.get("envelope").toString())
            .get(10, TimeUnit.SECONDS);
        tx.executeWithoutResult(
            s -> {
              var control = db.one("select * from system_control where id=true for update");
              int done =
                  db.exec(
                      "update publication_outbox set published_at=now(),lease_until=null where id=?"
                          + " and owner=? and generation=? and lease_until>clock_timestamp() and"
                          + " published_at is null and run_id=? and dispatch_epoch=?",
                      row.get("id"),
                      owner,
                      row.get("generation"),
                      control.get("run_id"),
                      control.get("dispatch_epoch"));
              if (done == 1)
                db.exec(
                    "update system_control set unpublished_events=unpublished_events-1 where"
                        + " id=true");
            });
      } catch (Exception ex) {
        return;
      }
    }
  }

  @KafkaListener(
      topics = {
        "#{__listener.topic('work.v1')}",
        "#{__listener.topic('provider-events.v1')}",
        "#{__listener.topic('domain-events.v1')}",
        "#{__listener.topic('failures.v1')}"
      },
      groupId = "#{__listener.group()}",
      autoStartup = "${mprp.worker:false}")
  public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
    try {
      tx.executeWithoutResult(s -> consumeDurably(record));
      ack.acknowledge();
    } catch (DomainException rejected) {
      tx.executeWithoutResult(
          s -> {
            quarantine(record.value(), record.topic(), "Domain envelope application rejected");
            var parsed = CanonicalJson.parse(record.value());
            UUID id = UUID.fromString(parsed.get("event_id").toString());
            if (db.scalar(
                    "select count(*) from consumer_receipt where consumer='worker.s1' and"
                        + " event_id=?",
                    Long.class,
                    id)
                == 0) receipt(id, CanonicalJson.sha256(parsed), "QUARANTINED");
          });
      ack.acknowledge();
    } catch (Exception transientFailure) {
      /* No offset advance before durable disposition. */
      throw transientFailure;
    }
  }

  @SuppressWarnings("unchecked")
  private void consumeDurably(ConsumerRecord<String, String> record) {
    Map<String, Object> e;
    try {
      require(
          record.value() != null
              && record.value().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 262144,
          400,
          "Invalid envelope size");
      e = CanonicalJson.parse(record.value());
    } catch (Exception invalid) {
      quarantine(record.value(), record.topic(), "Malformed envelope");
      return;
    }
    String consumer = "worker.s1", hash = CanonicalJson.sha256(e);
    UUID id;
    try {
      id = UUID.fromString(e.get("event_id").toString());
    } catch (Exception bad) {
      quarantine(record.value(), record.topic(), "Invalid event ID");
      return;
    }
    var old =
        db.maybe(
            "select digest from consumer_receipt where consumer=? and event_id=?", consumer, id);
    if (old.isPresent()) {
      if (!hash.equals(old.get().get("digest")))
        quarantine(record.value(), record.topic(), "Event identity payload conflict");
      return;
    }
    var control = db.one("select * from system_control where id=true");
    try {
      validateEnvelope(e, control, record.topic());
    } catch (DomainException bad) {
      quarantine(record.value(), record.topic(), "Envelope schema/environment/digest rejected");
      receipt(id, hash, "QUARANTINED");
      return;
    }
    String stream = record.topic();
    UUID aggregate = UUID.fromString(e.get("aggregate_id").toString());
    long seq = ((Number) e.get("stream_sequence")).longValue();
    db.exec(
        "insert into consumer_stream(consumer,stream,aggregate_id,sequence) values(?,?,?,0) on"
            + " conflict do nothing",
        consumer,
        stream,
        aggregate);
    long previous =
        db.scalar(
            "select sequence from consumer_stream where consumer=? and stream=? and aggregate_id=?"
                + " for update",
            Long.class,
            consumer,
            stream,
            aggregate);
    if (seq > previous + 1) {
      db.exec(
          "insert into deferred_event(consumer,event_id,envelope) values(?,?,?) on conflict do"
              + " nothing",
          consumer + ":" + stream,
          id,
          record.value());
      receipt(id, hash, "DEFERRED");
      return;
    }
    if (seq <= previous) {
      receipt(id, hash, "STALE");
      return;
    }
    apply(e, stream);
    db.exec(
        "update consumer_stream set sequence=? where consumer=? and stream=? and aggregate_id=?",
        seq,
        consumer,
        stream,
        aggregate);
    receipt(id, hash, "APPLIED");
  }

  @Scheduled(fixedDelay = 5000)
  public void discoverRetainedDeliveries() {
    if (!enabled) return;
    for (var row :
        db.query(
            "select * from publication_outbox where consumed_at is null and"
                + " created_at<clock_timestamp()-interval '30 seconds' order by created_at,id limit"
                + " 20")) {
      var record =
          new ConsumerRecord<String, String>(
              topic(row.get("stream").toString()),
              0,
              -1L,
              row.get("aggregate_id").toString(),
              row.get("envelope").toString());
      try {
        tx.executeWithoutResult(status -> consumeDurably(record));
      } catch (DomainException rejected) {
        tx.executeWithoutResult(
            status -> {
              quarantine(
                  record.value(),
                  record.topic(),
                  "Retained database reference application rejected");
              var envelope = CanonicalJson.parse(record.value());
              UUID id = UUID.fromString(envelope.get("event_id").toString());
              if (db.scalar(
                      "select count(*) from consumer_receipt where consumer='worker.s1' and"
                          + " event_id=?",
                      Long.class,
                      id)
                  == 0) receipt(id, CanonicalJson.sha256(envelope), "QUARANTINED");
            });
      } catch (RuntimeException unavailable) {
        return;
      }
    }
  }

  @Scheduled(fixedDelay = 1000)
  public void drainDeferred() {
    if (!enabled) return;
    for (var candidate :
        db.query(
            "select d.consumer,d.event_id,c.stream,c.aggregate_id from deferred_event d join"
                + " consumer_stream c on d.consumer=c.consumer||':'||c.stream and"
                + " d.envelope::jsonb->>'aggregate_id'=c.aggregate_id::text and"
                + " (d.envelope::jsonb->>'stream_sequence')::bigint=c.sequence+1 where not"
                + " exists(select 1 from deferred_failure f where f.consumer=d.consumer and"
                + " f.event_id=d.event_id) order by d.event_id limit 20")) {
      try {
        tx.executeWithoutResult(
            status -> {
              var stream =
                  db.one(
                      "select * from consumer_stream where consumer='worker.s1' and stream=? and"
                          + " aggregate_id=? for update",
                      candidate.get("stream"),
                      candidate.get("aggregate_id"));
              var row =
                  db.maybe(
                      "select * from deferred_event where consumer=? and event_id=? for update",
                      candidate.get("consumer"),
                      candidate.get("event_id"));
              if (row.isEmpty()) return;
              var envelope = CanonicalJson.parse(row.get().get("envelope").toString());
              validateEnvelope(
                  envelope,
                  db.one("select * from system_control where id=true"),
                  candidate.get("stream").toString());
              require(
                  candidate.get("event_id").equals(envelopeUuid(envelope, "event_id"))
                      && candidate
                          .get("aggregate_id")
                          .equals(envelopeUuid(envelope, "aggregate_id"))
                      && CanonicalJson.sha256(envelope)
                          .equals(
                              db.scalar(
                                  "select digest from consumer_receipt where consumer='worker.s1'"
                                      + " and event_id=?",
                                  String.class,
                                  candidate.get("event_id"))),
                  409,
                  "Deferred event identity conflicts with its retained receipt");
              long seq = envelopeInteger(envelope.get("stream_sequence"), true);
              if (seq != Db.number(stream, "sequence") + 1) return;
              apply(envelope, candidate.get("stream").toString());
              db.exec(
                  "update consumer_stream set sequence=? where consumer='worker.s1' and stream=?"
                      + " and aggregate_id=?",
                  seq,
                  candidate.get("stream"),
                  candidate.get("aggregate_id"));
              db.exec(
                  "delete from deferred_event where consumer=? and event_id=?",
                  candidate.get("consumer"),
                  candidate.get("event_id"));
            });
      } catch (DomainException rejected) {
        tx.executeWithoutResult(
            status -> {
              db.query(
                  "select pg_advisory_xact_lock(hashtextextended(?,0))",
                  "deferred:" + candidate.get("consumer") + candidate.get("event_id"));
              if (db.scalar(
                      "select count(*) from deferred_failure where consumer=? and event_id=?",
                      Long.class,
                      candidate.get("consumer"),
                      candidate.get("event_id"))
                  > 0) return;
              var row =
                  db.one(
                      "select envelope from deferred_event where consumer=? and event_id=?",
                      candidate.get("consumer"),
                      candidate.get("event_id"));
              UUID q =
                  quarantine(
                      row.get("envelope").toString(),
                      candidate.get("stream").toString(),
                      "Deferred reference application rejected; preceding work remains committed");
              db.exec(
                  "insert into deferred_failure(consumer,event_id,quarantine_id) values(?,?,?)",
                  candidate.get("consumer"),
                  candidate.get("event_id"),
                  q);
            });
      } catch (RuntimeException unavailable) {
        return;
      }
    }
  }

  /** Reject poison metadata before it can enter the sequence deferral queue. */
  private void validateEnvelope(
      Map<String, Object> envelope, Map<String, Object> control, String stream) {
    try {
      require(
          List.of("work.v1", "provider-events.v1", "domain-events.v1", "failures.v1").stream()
              .anyMatch(s -> topic(s).equals(stream)),
          400,
          "Foreign stream");
      require(
          envelope
              .keySet()
              .containsAll(
                  Set.of(
                      "event_id",
                      "schema_version",
                      "policy_version",
                      "environment_id",
                      "run_id",
                      "dispatch_epoch",
                      "aggregate_type",
                      "aggregate_id",
                      "aggregate_version",
                      "stream_sequence",
                      "merchant_id",
                      "event_type",
                      "occurred_at",
                      "correlation_id",
                      "causation_id",
                      "reference",
                      "payload_digest")),
          400,
          "Incomplete envelope");
      require(
          control.get("environment_id").toString().equals(envelope.get("environment_id"))
              && envelopeInteger(envelope.get("schema_version"), true) == 1
              && "mprp_v1".equals(envelope.get("policy_version")),
          400,
          "Foreign envelope");
      UUID event = envelopeUuid(envelope, "event_id");
      envelopeUuid(envelope, "aggregate_id");
      envelopeUuid(envelope, "correlation_id");
      envelopeUuid(envelope, "run_id");
      if (envelope.get("merchant_id") != null) envelopeUuid(envelope, "merchant_id");
      if (envelope.get("causation_id") != null) envelopeUuid(envelope, "causation_id");
      envelopeInteger(envelope.get("dispatch_epoch"), true);
      envelopeInteger(envelope.get("aggregate_version"), false);
      envelopeInteger(envelope.get("stream_sequence"), true);
      require(
          envelope.get("aggregate_type") instanceof String type
              && !type.isBlank()
              && type.length() <= 100
              && envelope.get("event_type") instanceof String eventType
              && !eventType.isBlank()
              && eventType.length() <= 100,
          400,
          "Invalid event type");
      java.time.Instant.parse((String) envelope.get("occurred_at"));
      require(envelope.get("reference") instanceof Map<?, ?>, 400, "Invalid reference object");
      @SuppressWarnings("unchecked")
      Map<String, Object> reference = (Map<String, Object>) envelope.get("reference");
      for (String key : List.of("operation_id", "matching_run_id", "export_id", "inbox_id"))
        if (reference.containsKey(key)) envelopeUuid(reference, key);
      require(
          !"OPERATION_COMPLETED".equals(envelope.get("event_type"))
              || reference.containsKey("operation_id"),
          400,
          "Missing completed operation reference");
      require(
          CanonicalJson.sha256(reference).equals(envelope.get("payload_digest")),
          400,
          "Envelope digest mismatch");
      if (!control.get("run_id").toString().equals(envelope.get("run_id"))) {
        var retained = db.maybe("select digest from publication_outbox where id=?", event);
        require(
            retained.isPresent()
                && CanonicalJson.sha256(envelope).equals(retained.get().get("digest")),
            400,
            "Unretained event from retired run");
      }
    } catch (DomainException rejected) {
      throw rejected;
    } catch (IllegalArgumentException
        | ClassCastException
        | NullPointerException
        | ArithmeticException
        | java.time.DateTimeException malformed) {
      throw new DomainException(400, "Invalid envelope metadata or reference");
    }
  }

  private UUID envelopeUuid(Map<String, Object> envelope, String field) {
    Object raw = envelope.get(field);
    require(raw instanceof String, 400, "Invalid UUID reference");
    UUID value = UUID.fromString((String) raw);
    require(value.toString().equals(raw), 400, "Noncanonical UUID reference");
    return value;
  }

  private long envelopeInteger(Object raw, boolean positive) {
    require(
        raw instanceof Byte
            || raw instanceof Short
            || raw instanceof Integer
            || raw instanceof Long
            || raw instanceof java.math.BigInteger,
        400,
        "Invalid integer metadata");
    long value = new java.math.BigInteger(raw.toString()).longValueExact();
    require(positive ? value > 0 : value >= 0, 400, "Invalid integer metadata");
    return value;
  }

  @SuppressWarnings("unchecked")
  private void apply(Map<String, Object> e, String stream) {
    // Failure references are inspection evidence, never a request to execute their original work.
    if (stream.equals(topic("failures.v1"))) return;
    Map<String, Object> ref = (Map<String, Object>) e.get("reference");
    if (ref.containsKey("operation_id"))
      db.exec(
          "update durable_job set due_at=least(due_at,now()) where operation_id=? and"
              + " state='READY'",
          UUID.fromString(ref.get("operation_id").toString()));
    if (ref.containsKey("matching_run_id"))
      db.exec(
          "update durable_job set due_at=least(due_at,clock_timestamp()) where kind='MATCHING' and"
              + " resource_id=? and state='READY'",
          UUID.fromString(ref.get("matching_run_id").toString()));
    if (ref.containsKey("export_id"))
      db.exec(
          "update durable_job set due_at=least(due_at,clock_timestamp()) where kind='EXPORT' and"
              + " resource_id=? and state='READY'",
          UUID.fromString(ref.get("export_id").toString()));
    if (ref.containsKey("inbox_id"))
      callbacks.apply(UUID.fromString(ref.get("inbox_id").toString()));
    if (e.get("event_type").equals("OPERATION_COMPLETED")) {
      var op =
          db.one(
              "select merchant_id from operation where id=?",
              UUID.fromString(ref.get("operation_id").toString()));
      for (var m :
          db.query(
              "select user_id from membership where merchant_id=? and active",
              op.get("merchant_id")))
        db.exec(
            "insert into notification(id,user_id,merchant_id,title,body,event_id)"
                + " values(?,?,?,?,?,?) on conflict(user_id,event_id) where event_id is not null do"
                + " nothing",
            UUID.randomUUID(),
            m.get("user_id"),
            op.get("merchant_id"),
            "Operation result available",
            "Inspect the operation record for its authoritative outcome.",
            UUID.fromString(e.get("event_id").toString()));
    }
  }

  @org.springframework.transaction.annotation.Transactional
  public void redrive(UUID quarantineId, UUID actor) {
    var saved =
        db.one(
            "select q.*,p.topic,p.envelope from quarantine q join quarantine_payload p on p.id=q.id"
                + " where q.id=? for update of q",
            quarantineId);
    var envelope = CanonicalJson.parse(saved.get("envelope").toString());
    var control = db.one("select * from system_control where id=true");
    validateEnvelope(envelope, control, saved.get("topic").toString());
    UUID eventId = UUID.fromString(envelope.get("event_id").toString());
    String digest = CanonicalJson.sha256(envelope);
    var original =
        db.maybe(
            "select digest from consumer_receipt where consumer='worker.s1' and event_id=?",
            eventId);
    require(
        original.isEmpty() || original.get().get("digest").equals(digest),
        409,
        "Event identity conflicts with retained original content");
    UUID aggregate = UUID.fromString(envelope.get("aggregate_id").toString());
    long seq = ((Number) envelope.get("stream_sequence")).longValue();
    String stream = saved.get("topic").toString();
    db.exec(
        "insert into consumer_stream(consumer,stream,aggregate_id,sequence)"
            + " values('worker.s1',?,?,0) on conflict do nothing",
        stream,
        aggregate);
    long previous =
        db.scalar(
            "select sequence from consumer_stream where consumer='worker.s1' and stream=? and"
                + " aggregate_id=? for update",
            Long.class,
            stream,
            aggregate);
    require(seq <= previous + 1, 409, "Recover the preceding stream gap before redrive");
    apply(envelope, stream);
    if (seq > previous)
      db.exec(
          "update consumer_stream set sequence=? where consumer='worker.s1' and stream=? and"
              + " aggregate_id=?",
          seq,
          stream,
          aggregate);
    db.exec(
        "delete from deferred_event where consumer=? and event_id=?",
        "worker.s1:" + stream,
        eventId);
    if (original.isEmpty()) receipt(eventId, digest, "APPLIED");
    db.exec(
        "insert into redrive_receipt(id,quarantine_id,actor_id,event_id,digest) values(?,?,?,?,?)",
        UUID.randomUUID(),
        quarantineId,
        actor,
        eventId,
        digest);
  }

  private void receipt(UUID id, String hash, String status) {
    db.exec(
        "insert into consumer_receipt(consumer,event_id,digest,disposition)"
            + " values('worker.s1',?,?,?)",
        id,
        hash,
        status);
    db.exec(
        "update publication_outbox set consumed_at=coalesce(consumed_at,clock_timestamp()) where"
            + " id=? and digest=?",
        id,
        hash);
  }

  private UUID quarantine(String raw, String topic, String reason) {
    UUID id = UUID.randomUUID();
    byte[] bytes =
        raw == null ? new byte[0] : raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    db.exec(
        "insert into quarantine(id,kind,digest,safe_reason) values(?,'KAFKA',?,?)",
        id,
        CanonicalJson.digest(bytes),
        reason);
    if (raw != null && bytes.length <= 262144)
      db.exec("insert into quarantine_payload(id,topic,envelope) values(?,?,?)", id, topic, raw);
    if (!reason.equals("Malformed failure"))
      events.emit(
          null, "failures.v1", id, "ENVELOPE_QUARANTINED", Map.of("quarantine_id", id.toString()));
    return id;
  }
}
