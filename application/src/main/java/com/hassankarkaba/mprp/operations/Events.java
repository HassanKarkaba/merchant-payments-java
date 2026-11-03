package com.hassankarkaba.mprp.operations;

import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Events {
  private final Db db;

  public Events(Db db) {
    this.db = db;
  }

  public UUID emit(
      UUID merchant, String stream, UUID aggregate, String event, Map<String, Object> reference) {
    var control = db.one("select * from system_control where id=true for update");
    db.exec(
        "insert into stream_head(stream,aggregate_id,sequence) values(?,?,0) on conflict do"
            + " nothing",
        stream,
        aggregate);
    long seq =
        db.scalar(
            "update stream_head set sequence=sequence+1 where stream=? and aggregate_id=? returning"
                + " sequence",
            Long.class,
            stream,
            aggregate);
    UUID id = UUID.randomUUID();
    var e = new TreeMap<String, Object>();
    e.put("event_id", id.toString());
    e.put("schema_version", 1);
    e.put("policy_version", "mprp_v1");
    e.put("environment_id", control.get("environment_id").toString());
    e.put("run_id", control.get("run_id").toString());
    e.put("dispatch_epoch", control.get("dispatch_epoch"));
    e.put("aggregate_type", "merchant_work");
    e.put("aggregate_id", aggregate.toString());
    e.put("aggregate_version", seq);
    e.put("stream_sequence", seq);
    e.put("merchant_id", merchant == null ? null : merchant.toString());
    e.put("event_type", event);
    e.put("occurred_at", CanonicalJson.instant(Instant.now()));
    e.put("correlation_id", id.toString());
    e.put("causation_id", null);
    e.put("reference", reference);
    e.put("payload_digest", CanonicalJson.sha256(reference));
    db.exec(
        "insert into"
            + " publication_outbox(id,merchant_id,aggregate_id,stream,sequence,envelope,digest)"
            + " values(?,?,?,?,?,?,?)",
        id,
        merchant,
        aggregate,
        stream,
        seq,
        CanonicalJson.string(e),
        CanonicalJson.sha256(e));
    db.exec("update system_control set unpublished_events=unpublished_events+1 where id=true");
    return id;
  }
}
