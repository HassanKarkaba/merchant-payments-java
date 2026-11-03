package com.hassankarkaba.mprp.operations;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.accounting.LedgerService;
import com.hassankarkaba.mprp.payments.PaymentService;
import com.hassankarkaba.mprp.protocol.*;
import com.hassankarkaba.mprp.settlements.FinancialWorkflowService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CallbackService {
  private final Db db;
  private final Events events;
  private final LedgerService ledger;
  private final PaymentService payments;
  private final FinancialWorkflowService workflows;
  private final AuditService audit;
  private final ProviderResultValidator validator;
  private final QuarantineService quarantine;
  private final ProviderHistoryService histories;

  public CallbackService(
      Db db,
      Events events,
      LedgerService ledger,
      PaymentService payments,
      FinancialWorkflowService workflows,
      AuditService audit,
      ProviderResultValidator validator,
      QuarantineService quarantine,
      ProviderHistoryService histories) {
    this.db = db;
    this.events = events;
    this.ledger = ledger;
    this.payments = payments;
    this.workflows = workflows;
    this.audit = audit;
    this.validator = validator;
    this.quarantine = quarantine;
    this.histories = histories;
  }

  @Transactional
  public UUID admit(Map<String, Object> e) {
    UUID id = UUID.fromString(e.get("event_id").toString()),
        merchant = UUID.fromString(e.get("provider_account_id").toString());
    var c = db.one("select * from system_control where id=true");
    require(
        c.get("environment_id").toString().equals(e.get("environment_id"))
            && c.get("provider_instance_id") != null
            && c.get("provider_instance_id").toString().equals(e.get("simulator_instance_id")),
        409,
        "Provider event identity mismatch");
    require(
        ((Number) e.get("schema_version")).intValue() == 1
            && "mprp_v1".equals(e.get("policy_version")),
        400,
        "Unsupported callback schema");
    String digest = CanonicalJson.sha256(e);
    var prior = db.maybe("select digest from callback_inbox where id=?", id);
    if (prior.isPresent()) {
      if (!digest.equals(prior.get().get("digest")))
        quarantine.conflict("CALLBACK", digest, "Authenticated event ID changed payload");
      require(digest.equals(prior.get().get("digest")), 409, "Conflicting event identity");
      return id;
    }
    db.exec(
        "insert into callback_inbox(id,digest,payload) values(?,?,?)",
        id,
        digest,
        CanonicalJson.string(e));
    events.emit(
        merchant,
        "provider-events.v1",
        merchant,
        "PROVIDER_EVENT_ACCEPTED",
        Map.of("inbox_id", id.toString()));
    return id;
  }

  @SuppressWarnings("unchecked")
  @Transactional
  public void apply(UUID id) {
    var inbox = db.one("select * from callback_inbox where id=? for no key update", id);
    if (inbox.get("state").equals("APPLIED")) return;
    var e = CanonicalJson.parse(inbox.get("payload").toString());
    require(
        CanonicalJson.sha256(e).equals(inbox.get("digest")),
        409,
        "Authenticated inbox integrity failure");
    UUID merchant = UUID.fromString(e.get("provider_account_id").toString());
    db.lockMerchant(merchant);
    db.one("select * from system_control where id=true for update");
    Map<String, Object> r = (Map<String, Object>) e.get("result");
    require(
        r != null
            && "USD".equals(r.get("currency"))
            && merchant.toString().equals(r.get("provider_account_id")),
        409,
        "Invalid provider event result");
    var op =
        db.maybe(
            "select * from operation where provider_key=? and merchant_id=? for update",
            r.get("operation_key"),
            merchant);
    if (op.isPresent()) {
      var o = op.get();
      boolean preposted = false;
      if (Boolean.TRUE.equals(r.get("history_required"))) {
        histories.validateSummary(o, r);
        var expanded = histories.completed(Db.uuid(o, "id"), r);
        if (expanded.isPresent() && histories.preposted(Db.uuid(o, "id"))) {
          r = expanded.get();
          preposted = true;
        } else {
          var fromInbox = histories.completedInbox(id, r);
          if (fromInbox.isPresent() && histories.prepostedInbox(id)) {
            r = fromInbox.get();
            preposted = true;
          } else {
            if (Set.of("QUEUED", "FAILED", "CANCELLED").contains(o.get("status"))) {
              var job =
                  db.maybe(
                      "select * from durable_job where operation_id=? for update", o.get("id"));
              if (job.isPresent()) {
                boolean terminal = Set.of("DONE", "CANCELLED").contains(job.get().get("state"));
                db.exec(
                    "update durable_job set"
                        + " state='READY',generation=generation+1,lease_until=null,due_at=clock_timestamp()"
                        + " where id=?",
                    job.get().get("id"));
                if (terminal)
                  db.exec(
                      "update system_control set unfinished_jobs=unfinished_jobs+1 where id=true");
              }
              db.exec(
                  "update operation set status='UNKNOWN',reason='Authenticated provider commitment"
                      + " retained; complete history"
                      + " pending',updated_at=clock_timestamp(),version=version+1 where id=?",
                  o.get("id"));
            } else if (!o.get("status").equals("SUCCEEDED"))
              db.exec(
                  "update durable_job set due_at=least(due_at,clock_timestamp()) where"
                      + " operation_id=? and state='READY'",
                  o.get("id"));
            return;
          }
        }
      }
      validator.validate(merchant, o, r);
      require(
          o.get("fingerprint").equals(r.get("payload_hash"))
              && o.get("resource_id").toString().equals(r.get("resource_id")),
          409,
          "Event operation mismatch");
      List<Map<String, Object>> sourceRows = (List<Map<String, Object>>) r.get("sources");
      if (preposted)
        sourceRows =
            sourceRows.stream()
                .filter(
                    source ->
                        Set.of("SETTLEMENT", "PAYOUT", "BATCH_ZERO")
                            .contains(source.get("event_type")))
                .toList();
      ledger.apply(merchant, Db.uuid(o, "id"), sourceRows);
      payments.applyResult(merchant, o, r);
      workflows.applyProviderResult(
          merchant, o.get("kind").toString(), Db.uuid(o, "resource_id"), r);
      if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(o.get("status"))) {
        db.exec(
            "update operation set status=?,safe_result=?,reason='Authenticated provider event"
                + " applied',version=version+1,updated_at=now() where id=?",
            r.get("state").equals("SUCCEEDED") ? "SUCCEEDED" : "FAILED",
            CanonicalJson.string(r),
            o.get("id"));
        db.exec(
            "update durable_job set state='DONE',generation=generation+1,lease_until=null where"
                + " operation_id=?",
            o.get("id"));
        db.exec("update system_control set unfinished_jobs=unfinished_jobs-1 where id=true");
      }
    } else {
      boolean preposted = false;
      if (Boolean.TRUE.equals(r.get("history_required"))) {
        var expanded = histories.completedInbox(id, r);
        if (expanded.isEmpty() || !histories.prepostedInbox(id)) return;
        r = expanded.get();
        preposted = true;
      }
      validator.validate(merchant, null, r);
      require(
          Set.of("dispute_outcome", "settlement_completed").contains(e.get("event_type")),
          409,
          "Provider effect lacks retained admission");
      List<Map<String, Object>> sourceRows = (List<Map<String, Object>>) r.get("sources");
      if (preposted)
        sourceRows =
            sourceRows.stream()
                .filter(
                    source ->
                        Set.of("SETTLEMENT", "PAYOUT", "BATCH_ZERO")
                            .contains(source.get("event_type")))
                .toList();
      ledger.apply(merchant, null, sourceRows);
      workflows.applyProviderResult(
          merchant,
          e.get("event_type").equals("settlement_completed") ? "flush" : "resolve_dispute",
          UUID.fromString(r.get("resource_id").toString()),
          r);
    }
    db.exec("update callback_inbox set state='APPLIED' where id=?", id);
    audit.record(merchant, null, "provider.event.applied", id, e.get("event_type").toString());
  }
}
