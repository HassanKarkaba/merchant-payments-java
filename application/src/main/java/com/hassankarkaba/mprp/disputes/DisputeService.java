package com.hassankarkaba.mprp.disputes;

import static com.hassankarkaba.mprp.operations.DomainException.require;
import static com.hassankarkaba.mprp.settlements.WorkflowSupport.*;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DisputeService {
  private final Db db;
  private final Authz auth;
  private final OperationService operations;
  private final com.hassankarkaba.mprp.settlements.WorkflowNotice audit;

  public DisputeService(
      Db db,
      Authz auth,
      OperationService operations,
      com.hassankarkaba.mprp.settlements.WorkflowNotice audit) {
    this.db = db;
    this.auth = auth;
    this.operations = operations;
    this.audit = audit;
  }

  @Transactional
  public UUID respond(UUID merchant, UUID dispute, String response, String key) {
    UUID actor = auth.require(merchant, "OWNER", "FINANCE");
    response = text(response, 2000);
    db.lockMerchant(merchant);
    var d =
        db.one("select * from dispute where id=? and merchant_id=? for update", dispute, merchant);
    var payload = map("response", response);
    var replay = receipt(db, merchant, actor, "DISPUTE_RESPONSE", dispute, key, payload);
    if (replay.isPresent()) return replay.get();
    require("OPEN".equals(s(d, "status")), 409, "This dispute is not open for a response");
    require(
        Instant.now().isBefore(time(d.get("response_deadline"))),
        409,
        "The response deadline has passed");
    activeMerchant(db, merchant);
    UUID operation =
        operations.admit(
            merchant,
            actor,
            "dispute_response",
            dispute,
            key,
            map("response", response, "payment_id", id(d, "payment_id").toString()));
    db.exec(
        "update dispute set response_text=?,response_operation_id=? where id=?",
        response,
        operation,
        dispute);
    saveReceipt(db, merchant, actor, "DISPUTE_RESPONSE", dispute, key, payload, operation);
    return operation;
  }

  public void validateDispatch(Map<String, Object> op) {
    var d =
        db.one(
            "select * from dispute where id=? and merchant_id=? for update",
            id(op, "resource_id"),
            id(op, "merchant_id"));
    require(
        "OPEN".equals(s(d, "status")) && Instant.now().isBefore(time(d.get("response_deadline"))),
        409,
        "Dispute is no longer open before its response deadline");
    UUID actor = op.get("actor_id") != null ? id(op, "actor_id") : id(op, "actor");
    member(db, actor, id(op, "merchant_id"), "OWNER", "FINANCE");
    activeMerchant(db, id(op, "merchant_id"));
  }

  @SuppressWarnings("unchecked")
  public void apply(UUID merchant, String kind, UUID resource, Map<String, Object> result) {
    Object raw = result.get("dispute");
    if (!(raw instanceof Map<?, ?>)) return;
    Map<String, Object> d = (Map<String, Object>) raw;
    UUID id = UUID.fromString(s(d, "id"));
    UUID payment = UUID.fromString(s(d, "payment_id"));
    long version = n(d, "version"), amount = n(d, "amount_minor");
    String status = s(d, "status");
    require(
        Set.of("OPEN", "RESPONDED", "WON", "LOST").contains(status) && amount > 0,
        409,
        "Invalid dispute projection");
    var old =
        db.maybe(
            "select * from dispute where merchant_id=? and payment_id=? for update",
            merchant,
            payment);
    if (old.isPresent()) {
      var p = old.get();
      require(
          s(p, "provider_id").equals(id.toString()) && n(p, "amount_minor") == amount,
          409,
          "Dispute identity changed");
      if (version <= n(p, "version")) return;
      if (Set.of("WON", "LOST").contains(s(p, "status")))
        require(s(p, "status").equals(status), 409, "Terminal dispute outcome cannot change");
      db.exec("update dispute set status=?,version=? where id=?", status, version, id(p, "id"));
    } else {
      db.exec(
          "insert into"
              + " dispute(id,merchant_id,payment_id,provider_id,amount_minor,status,version,response_deadline)"
              + " values(?,?,?,?,?,?,?,?)",
          id,
          merchant,
          payment,
          id.toString(),
          amount,
          status,
          version,
          Timestamp.from(time(d.get("response_deadline"))));
    }
    db.exec(
        "update payment set disputed_minor=?,version=version+1 where merchant_id=? and id=?",
        status.equals("WON") ? 0 : amount,
        merchant,
        payment);
    audit.record(
        merchant, null, "DISPUTE_" + status, id, "Authenticated ordered provider projection");
  }
}
