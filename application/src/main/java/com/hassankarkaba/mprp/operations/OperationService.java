package com.hassankarkaba.mprp.operations;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationService {
  private final Db db;
  private final AuditService audit;
  private final Events events;
  private final Authz auth;
  private final Readiness ready;

  public OperationService(Db db, AuditService audit, Events events, Authz auth, Readiness ready) {
    this.db = db;
    this.audit = audit;
    this.events = events;
    this.auth = auth;
    this.ready = ready;
  }

  @Transactional
  public UUID admit(
      UUID merchant,
      UUID actor,
      String kind,
      UUID resource,
      String key,
      Map<String, Object> payload) {
    require(key != null && key.matches("[A-Za-z0-9_-]{32,128}"), 400, "Invalid action key");
    byte[] bytes = CanonicalJson.bytes(payload);
    require(bytes.length <= 16384, 413, "Operation payload too large");
    String hash = CanonicalJson.sha256(payload);
    db.lockMerchant(merchant);
    var old =
        db.maybe(
            "select * from operation where merchant_id=? and actor_id=? and kind=? and"
                + " resource_id=? and action_key=?",
            merchant,
            actor,
            kind,
            resource,
            key);
    if (old.isPresent()) {
      require(
          CanonicalJson.sha256(CanonicalJson.parse(old.get().get("payload").toString()))
              .equals(old.get().get("fingerprint")),
          503,
          "Retained operation integrity failure");
      require(
          hash.equals(old.get().get("fingerprint")),
          409,
          "Action key was used with different input");
      return Db.uuid(old.get(), "id");
    }
    require(
        Boolean.TRUE.equals(ready.capabilities().get("financial_admission")),
        503,
        "Financial admission capabilities are not ready");
    require(audit.admissionIntegrity(), 503, "Receipt or audit integrity failure");
    var control = db.one("select * from system_control where id=true for update");
    require(
        !(Boolean) control.get("restore_hold"), 503, "Financial admission is held for recovery");
    require(
        Db.number(control, "unfinished_jobs") < 10000
            && Db.number(control, "unpublished_events") < 100000,
        503,
        "Admission backlog is full");
    require(
        "ACTIVE"
            .equals(db.scalar("select status from merchant where id=?", String.class, merchant)),
        409,
        "Merchant is not active");
    var now = Instant.now();
    require(
        !((Timestamp) control.get("clock_at")).toInstant().isAfter(now.plusSeconds(2)),
        503,
        "Clock safety hold");
    UUID id = UUID.randomUUID();
    String providerKey = "java1_" + id.toString().replace("-", "");
    db.exec(
        "insert into"
            + " operation(id,merchant_id,actor_id,kind,resource_id,action_key,fingerprint,provider_key,payload,status)"
            + " values(?,?,?,?,?,?,?,?,?,'QUEUED')",
        id,
        merchant,
        actor,
        kind,
        resource,
        key,
        hash,
        providerKey,
        CanonicalJson.string(payload));
    db.exec(
        "insert into durable_job(id,operation_id,kind,resource_id,merchant_id) values(?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        "OPERATION",
        resource,
        merchant);
    db.exec(
        "update system_control set"
            + " unfinished_jobs=unfinished_jobs+1,clock_at=greatest(clock_at,now()) where id=true");
    events.emit(
        merchant, "work.v1", merchant, "OPERATION_ACCEPTED", Map.of("operation_id", id.toString()));
    audit.record(merchant, actor, "operation.admitted", id, kind);
    return id;
  }

  @Transactional
  public void cancel(
      UUID merchant, UUID operation, UUID actor, String key, long expected, String proposalHash) {
    auth.require(merchant, "OWNER", "FINANCE");
    require(
        key != null && key.matches("[A-Za-z0-9_-]{32,128}"),
        400,
        "Invalid cancellation action key");
    db.lockMerchant(merchant);
    String fingerprint =
        CanonicalJson.sha256(
            Map.of(
                "expected_version", expected, "proposal_hash", proposalHash, "decision", "CANCEL"));
    var receipt =
        db.maybe(
            "select fingerprint from operation_cancel_receipt where merchant_id=? and"
                + " operation_id=? and actor_id=? and action_key=?",
            merchant,
            operation,
            actor,
            key);
    if (receipt.isPresent()) {
      require(
          fingerprint.equals(receipt.get().get("fingerprint")),
          409,
          "Cancellation key reused with different input");
      return;
    }
    var op =
        db.one(
            "select * from operation where id=? and merchant_id=? for update", operation, merchant);
    require(
        Db.number(op, "version") == expected && op.get("fingerprint").equals(proposalHash),
        409,
        "Operation changed; refresh and inspect");
    cancel(merchant, operation, actor);
    db.exec(
        "insert into"
            + " operation_cancel_receipt(id,merchant_id,operation_id,actor_id,action_key,fingerprint,expected_version,proposal_hash,decision)"
            + " values(?,?,?,?,?,?,?,?,'CANCEL')",
        UUID.randomUUID(),
        merchant,
        operation,
        actor,
        key,
        fingerprint,
        expected,
        proposalHash);
  }

  @Transactional
  public void cancel(UUID merchant, UUID operation, UUID actor) {
    auth.require(merchant, "OWNER", "FINANCE");
    db.lockMerchant(merchant);
    var row =
        db.one(
            "select * from operation where id=? and merchant_id=? for update", operation, merchant);
    if ("CANCELLED".equals(row.get("status"))) return;
    require(
        "QUEUED".equals(row.get("status")),
        409,
        "Dispatch has claimed this operation; outcome must be inspected");
    require(
        db.exec(
                "update durable_job set state='CANCELLED',generation=generation+1 where"
                    + " operation_id=? and state='READY'",
                operation)
            == 1,
        409,
        "Dispatch has already claimed this operation");
    db.exec(
        "update operation set status='CANCELLED',version=version+1,updated_at=now() where id=?",
        operation);
    releaseRefund(operation);
    if (row.get("kind").equals("authorize"))
      db.exec(
          "update payment set status='CANCELLED',version=version+1 where id=? and"
              + " status='PROCESSING'",
          row.get("resource_id"));
    db.exec(
        "update payout set reserved=false,status='CANCELLED',version=version+1 where operation_id=?"
            + " and status='APPROVED'",
        operation);
    db.exec("update system_control set unfinished_jobs=unfinished_jobs-1 where id=true");
    audit.record(merchant, actor, "operation.cancelled", operation, "Provably unsent");
  }

  public void releaseRefund(UUID operation) {
    var reservation =
        db.maybe(
            "select * from refund_reservation where operation_id=? and state='ACTIVE' for update",
            operation);
    if (reservation.isPresent()) {
      var r = reservation.get();
      db.exec(
          "update payment set reserved_refund_minor=reserved_refund_minor-?,version=version+1 where"
              + " id=?",
          r.get("amount_minor"),
          r.get("payment_id"));
      db.exec("update refund_reservation set state='RELEASED' where id=?", r.get("id"));
    }
  }

  public Map<String, Object> status(UUID merchant, UUID operation) {
    try {
      auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    } catch (DomainException denied) {
      if (denied.status() != 404) throw denied;
      auth.platform("OPERATIONS", "AUDITOR");
    }
    var r =
        db.one(
            "select o.*,p.status as business_state from operation o left join payment p on"
                + " p.id=o.resource_id where o.id=? and o.merchant_id=?",
            operation,
            merchant);
    return new OperationStatusView(
            operation,
            OperationStatusView.State.valueOf(r.get("status").toString()),
            (String) r.get("business_state"),
            r.get("reason") == null
                ? "Inspect the retained execution state"
                : r.get("reason").toString(),
            ((Timestamp) r.get("updated_at")).toInstant(),
            ((Number) r.get("version")).longValue(),
            "/m/" + merchant + "/operations/" + operation + "/")
        .fields();
  }
}
