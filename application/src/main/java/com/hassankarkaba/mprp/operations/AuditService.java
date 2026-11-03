package com.hassankarkaba.mprp.operations;

import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
  private final Db db;

  public AuditService(Db db) {
    this.db = db;
  }

  @Transactional
  public void record(UUID merchant, UUID actor, String action, UUID resource, String reason) {
    String stream = merchant == null ? "platform" : merchant.toString();
    String zero = "0".repeat(64);
    db.exec(
        "insert into audit_head(stream,sequence,digest) values(?,0,?) on conflict do nothing",
        stream,
        zero);
    var head = db.one("select * from audit_head where stream=? for update", stream);
    long seq = Db.number(head, "sequence") + 1;
    var e = new TreeMap<String, Object>();
    e.put("stream", stream);
    e.put("sequence", seq);
    e.put("actor_id", actor == null ? null : actor.toString());
    e.put("merchant_id", merchant == null ? null : merchant.toString());
    e.put("action", action);
    e.put("resource_id", resource == null ? null : resource.toString());
    e.put("reason", reason == null ? "" : reason);
    e.put("policy_version", "mprp_v1");
    e.put("occurred_at", CanonicalJson.instant(Instant.now()));
    e.put("previous_hash", head.get("digest"));
    String hash = CanonicalJson.sha256(e);
    db.exec(
        "insert into"
            + " audit_event(id,stream,sequence,merchant_id,actor_id,action,resource_id,reason,previous_hash,digest,canonical)"
            + " values(?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        stream,
        seq,
        merchant,
        actor,
        action,
        resource,
        reason == null ? "" : reason,
        head.get("digest"),
        hash,
        CanonicalJson.string(e));
    db.exec("update audit_head set sequence=?,digest=? where stream=?", seq, hash, stream);
  }

  public boolean admissionIntegrity() {
    for (var h : db.query("select * from audit_head")) {
      if (Db.number(h, "sequence") == 0) continue;
      var tail =
          db.maybe(
              "select digest,canonical from audit_event where stream=? and sequence=?",
              h.get("stream"),
              h.get("sequence"));
      if (tail.isEmpty()
          || !h.get("digest").equals(tail.get().get("digest"))
          || !CanonicalJson.sha256(CanonicalJson.parse(tail.get().get("canonical").toString()))
              .equals(h.get("digest"))) return false;
    }
    return true;
  }

  public boolean verify() {
    for (var h : db.query("select * from audit_head")) {
      String prior = "0".repeat(64);
      long seq = 0;
      for (var e :
          db.query("select * from audit_event where stream=? order by sequence", h.get("stream"))) {
        if (!prior.equals(e.get("previous_hash"))
            || Db.number(e, "sequence") != ++seq
            || !CanonicalJson.sha256(CanonicalJson.parse(e.get("canonical").toString()))
                .equals(e.get("digest"))) return false;
        prior = e.get("digest").toString();
      }
      if (seq != Db.number(h, "sequence") || !prior.equals(h.get("digest"))) return false;
    }
    return true;
  }
}
