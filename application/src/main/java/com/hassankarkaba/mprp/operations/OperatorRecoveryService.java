package com.hassankarkaba.mprp.operations;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorRecoveryService {
  private final Db db;
  private final Authz auth;
  private final KafkaTransport kafka;
  private final AuditService audit;

  public OperatorRecoveryService(Db db, Authz auth, KafkaTransport kafka, AuditService audit) {
    this.db = db;
    this.auth = auth;
    this.kafka = kafka;
    this.audit = audit;
  }

  @Transactional
  public void job(UUID job, String reason) {
    UUID actor = auth.platform("OPERATIONS");
    reason = reason(reason);
    var initial = db.one("select * from durable_job where id=? and operation_id is not null", job);
    UUID merchant = Db.uuid(initial, "merchant_id");
    db.lockMerchant(merchant);
    var j = db.one("select * from durable_job where id=? for update", job);
    var op = db.one("select * from operation where id=? for update", j.get("operation_id"));
    require(
        !Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(op.get("status")),
        409,
        "A terminal operation cannot be dispatched again");
    require(
        !j.get("state").equals("RUNNING")
            || j.get("lease_until") == null
            || !((java.sql.Timestamp) j.get("lease_until"))
                .toInstant()
                .isAfter(java.time.Instant.now()),
        409,
        "Current dispatch lease has not expired");
    require(
        CanonicalJson.sha256(CanonicalJson.parse(op.get("payload").toString()))
            .equals(op.get("fingerprint")),
        409,
        "Original operation integrity failed");
    db.exec(
        "update durable_job set"
            + " state='READY',generation=generation+1,owner=null,lease_until=null,due_at=now()"
            + " where id=?",
        job);
    audit.record(merchant, actor, "job.redriven", job, reason);
  }

  @Transactional
  public void quarantine(UUID id, String reason) {
    UUID actor = auth.platform("OPERATIONS");
    reason = reason(reason);
    kafka.redrive(id, actor);
    audit.record(null, actor, "quarantine.redriven", id, reason);
  }

  private String reason(String value) {
    require(
        value != null && !value.trim().isEmpty() && value.trim().length() <= 2000,
        400,
        "A reason of1–2000 characters is required");
    return value.trim();
  }
}
