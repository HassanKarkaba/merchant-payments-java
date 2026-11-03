package com.hassankarkaba.mprp.operations;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.accounting.LedgerService;
import com.hassankarkaba.mprp.payments.PaymentService;
import com.hassankarkaba.mprp.protocol.*;
import com.hassankarkaba.mprp.settlements.FinancialWorkflowService;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OperationWorker {
  private final Db db;
  private final TransactionTemplate tx;
  private final ProviderClient provider;
  private final LedgerService ledger;
  private final PaymentService payments;
  private final FinancialWorkflowService workflows;
  private final AuditService audit;
  private final Events events;
  private final OperationService ops;
  private final ProviderResultValidator validator;
  private final ProviderHistoryService histories;
  private final UUID owner = UUID.randomUUID();
  private final boolean enabled;

  public OperationWorker(
      Db db,
      org.springframework.transaction.PlatformTransactionManager manager,
      ProviderClient provider,
      LedgerService ledger,
      PaymentService payments,
      FinancialWorkflowService workflows,
      AuditService audit,
      Events events,
      OperationService ops,
      ProviderResultValidator validator,
      ProviderHistoryService histories,
      @Value("${mprp.worker:false}") boolean enabled) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.provider = provider;
    this.ledger = ledger;
    this.payments = payments;
    this.workflows = workflows;
    this.audit = audit;
    this.events = events;
    this.ops = ops;
    this.validator = validator;
    this.histories = histories;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 1000)
  public void discover() {
    if (!enabled) return;
    try {
      tx.executeWithoutResult(
          s -> db.exec("update system_control set worker_at=now() where id=true"));
      for (var candidate :
          db.query(
              "select j.id,j.merchant_id from durable_job j where j.operation_id is not null and"
                  + " ((j.state='READY' and j.due_at<=now()) or (j.state='RUNNING' and"
                  + " j.lease_until<clock_timestamp())) order by j.due_at,j.id limit 10")) {
        Map<String, Object> claim =
            tx.execute(s -> claim(Db.uuid(candidate, "id"), Db.uuid(candidate, "merchant_id")));
        if (claim != null) execute(claim);
      }
    } catch (Exception ignored) {
      /* Durable state remains discoverable. No secrets or provider payload in logs. */
    }
  }

  private Map<String, Object> claim(UUID id, UUID merchant) {
    var preview =
        db.one(
            "select o.* from operation o join durable_job j on j.operation_id=o.id where j.id=?",
            id);
    if (preview.get("status").equals("QUEUED") && !preview.get("kind").equals("authorize"))
      workflows.lockDispatchActors(preview);
    db.lockMerchant(merchant);
    var c = db.one("select * from system_control where id=true for update");
    var maybe =
        db.maybe(
            "select * from durable_job where id=? and ((state='READY' and due_at<=now()) or"
                + " (state='RUNNING' and lease_until<clock_timestamp())) for update skip locked",
            id);
    if (maybe.isEmpty()) return null;
    var job = maybe.get();
    var op = db.one("select * from operation where id=? for update", job.get("operation_id"));
    if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(op.get("status"))) {
      db.exec("update durable_job set state='DONE',generation=generation+1 where id=?", id);
      return null;
    }
    boolean resolve = !op.get("status").equals("QUEUED");
    if ((Boolean) c.get("restore_hold") && !resolve) return null;
    if (!resolve) {
      try {
        ledger.requirePostingReady();
        validateHumanDispatch(op);
        workflows.validateDispatch(op);
      } catch (DomainException ex) {
        if (ex.status() == 503) {
          db.exec(
              "update durable_job set due_at=now()+interval '5 seconds',last_error=? where id=?",
              ex.getMessage(),
              id);
          db.exec("update operation set reason=? where id=?", ex.getMessage(), op.get("id"));
          return null;
        }
        Map<String, Object> no = Map.of("state", "NO_EFFECT", "sources", List.of());
        payments.applyResult(merchant, op, no);
        workflows.applyProviderResult(
            merchant, op.get("kind").toString(), Db.uuid(op, "resource_id"), no);
        db.exec(
            "update operation set status='FAILED',reason=?,updated_at=now(),version=version+1 where"
                + " id=?",
            ex.getMessage(),
            op.get("id"));
        db.exec("update durable_job set state='DONE',generation=generation+1 where id=?", id);
        db.exec("update system_control set unfinished_jobs=unfinished_jobs-1 where id=true");
        return null;
      }
    }
    long generation = Db.number(job, "generation") + 1;
    db.exec(
        "update durable_job set"
            + " state='RUNNING',owner=?,generation=?,lease_until=clock_timestamp()+interval '30"
            + " seconds',run_id=?,dispatch_epoch=?,attempts=attempts+1 where id=?",
        owner,
        generation,
        c.get("run_id"),
        c.get("dispatch_epoch"),
        id);
    db.exec(
        "update operation set status='DISPATCHING',updated_at=now(),version=version+1 where id=?",
        op.get("id"));
    var request = new TreeMap<String, Object>(CanonicalJson.parse(op.get("payload").toString()));
    request.put("payload", CanonicalJson.parse(op.get("payload").toString()));
    request.put("payload_hash", op.get("fingerprint"));
    request.put("operation_key", op.get("provider_key"));
    request.put("provider_account_id", merchant.toString());
    request.put("merchant_id", merchant.toString());
    request.put("resource_id", op.get("resource_id").toString());
    request.put("kind", op.get("kind"));
    request.put("environment_id", c.get("environment_id").toString());
    request.put("main_instance_id", c.get("instance_id").toString());
    request.put("instance_id", c.get("instance_id").toString());
    request.put("dispatch_epoch", c.get("dispatch_epoch"));
    request.put("schema_version", 1);
    request.put("policy_version", "mprp_v1");
    var claim = new HashMap<String, Object>(op);
    claim.put("job_id", id);
    claim.put("generation", generation);
    claim.put("run_id", c.get("run_id"));
    claim.put("epoch", c.get("dispatch_epoch"));
    claim.put("request", request);
    claim.put("resolve", resolve);
    claim.put("owner", owner);
    return claim;
  }

  private void validateHumanDispatch(Map<String, Object> op) {
    require(
        "ACTIVE"
            .equals(
                db.scalar(
                    "select status from merchant where id=?", String.class, op.get("merchant_id"))),
        409,
        "Merchant no longer permits unsent financial dispatch");
    if (op.get("kind").equals("authorize")) return;
    if (Set.of("flush", "correction", "open_dispute", "resolve_dispute").contains(op.get("kind"))) {
      require(
          db.scalar(
                  "select count(*) from app_user where id=? and active and verified and"
                      + " totp_confirmed and platform_role='OPERATIONS'",
                  Long.class,
                  op.get("actor_id"))
              == 1,
          403,
          "Current operator authority required");
      return;
    }
    var actor =
        db.maybe(
            "select u.id from app_user u join membership m on m.user_id=u.id join merchant t on"
                + " t.id=m.merchant_id where u.id=? and u.active and u.verified and"
                + " u.totp_confirmed and m.merchant_id=? and m.active and m.role"
                + " in('OWNER','FINANCE') and t.status='ACTIVE'",
            op.get("actor_id"),
            op.get("merchant_id"));
    require(actor.isPresent(), 403, "Current financial actor authority no longer permits dispatch");
  }

  @SuppressWarnings("unchecked")
  private void execute(Map<String, Object> claim) {
    try {
      var cached = histories.cachedSummary(Db.uuid(claim, "id"));
      Map<String, Object> result =
          cached.orElseGet(
              () ->
                  provider.call(
                      (Boolean) claim.get("resolve")
                          ? "/v1/resolve-key/"
                          : "/v1/" + claim.get("kind") + "/",
                      (Map<String, Object>) claim.get("request")));
      if (Boolean.TRUE.equals(result.get("history_required"))) {
        var expanded = histories.expand(claim, result);
        if (expanded.isEmpty()) {
          historyPending(
              claim,
              histories.manual(Db.uuid(claim, "id")),
              histories.message(Db.uuid(claim, "id")));
          return;
        }
        result = expanded.get();
        validateResult(claim, result);
        if (!histories.postChunk(claim, result, ledger)) {
          historyPending(
              claim,
              false,
              "Authenticated provider history is being applied in bounded journal chunks");
          return;
        }
        claim.put("history_preposted", true);
      } else validateResult(claim, result);
      Map<String, Object> completeResult = result;
      tx.executeWithoutResult(s -> complete(claim, completeResult));
    } catch (Exception ex) {
      tx.executeWithoutResult(
          s -> {
            db.one("select * from system_control where id=true for update");
            if (fenced(claim)) {
              db.exec(
                  "update operation set status='UNKNOWN',reason='Provider outcome uncertain;"
                      + " original key retained',updated_at=now(),version=version+1 where id=?",
                  claim.get("id"));
              int attempt =
                  db.scalar(
                      "select attempts from durable_job where id=?",
                      Integer.class,
                      claim.get("job_id"));
              boolean manual =
                  db.scalar(
                      "select created_at<now()-interval '24 hours' from operation where id=?",
                      Boolean.class,
                      claim.get("id"));
              int delay = new int[] {1, 5, 30, 120}[Math.min(attempt - 1, 3)];
              db.exec(
                  "update durable_job set state=?,due_at=now()+(? * interval '1"
                      + " second'),lease_until=null,last_error='UNKNOWN' where id=?",
                  manual ? "MANUAL" : "READY",
                  delay,
                  claim.get("job_id"));
            }
          });
    }
  }

  private void historyPending(Map<String, Object> claim, boolean manual, String reason) {
    tx.executeWithoutResult(
        s -> {
          db.lockMerchant(Db.uuid(claim, "merchant_id"));
          if (!histories.isCurrentClaim(claim)) return;
          String safe =
              reason == null ? "Authenticated provider history continuation pending" : reason;
          db.exec(
              "update operation set"
                  + " status='UNKNOWN',reason=?,updated_at=clock_timestamp(),version=version+1"
                  + " where id=?",
              safe,
              claim.get("id"));
          db.exec(
              "update durable_job set state=?,lease_until=null,due_at=clock_timestamp()+interval '1"
                  + " second',last_error=? where id=?",
              manual ? "MANUAL" : "READY",
              safe.substring(0, Math.min(safe.length(), 200)),
              claim.get("job_id"));
        });
  }

  private boolean fenced(Map<String, Object> c) {
    return db.scalar(
            "select count(*) from durable_job j join system_control s on s.id=true where j.id=? and"
                + " j.owner=? and j.generation=? and j.run_id=s.run_id and"
                + " j.dispatch_epoch=s.dispatch_epoch and j.lease_until>clock_timestamp() and"
                + " j.state='RUNNING'",
            Long.class,
            c.get("job_id"),
            owner,
            c.get("generation"))
        == 1;
  }

  @SuppressWarnings("unchecked")
  private void validateResult(Map<String, Object> op, Map<String, Object> r) {
    validator.validate(Db.uuid(op, "merchant_id"), op, r);
    require(
        op.get("provider_key").equals(r.get("operation_key"))
            && op.get("fingerprint").equals(r.get("payload_hash")),
        409,
        "Provider operation identity mismatch");
    require(
        op.get("merchant_id").toString().equals(r.get("provider_account_id")),
        409,
        "Provider account mismatch");
    require("USD".equals(r.get("currency")), 409, "Provider currency mismatch");
    require(
        Set.of("SUCCEEDED", "FAILED", "NO_EFFECT").contains(r.get("state")),
        409,
        "Provider result is not terminal");
    var control = db.one("select provider_instance_id from system_control where id=true");
    require(
        control.get("provider_instance_id") != null
            && control
                .get("provider_instance_id")
                .toString()
                .equals(r.getOrDefault("simulator_instance_id", r.get("instance_id"))),
        409,
        "Provider instance mismatch");
    require(
        op.get("resource_id").toString().equals(r.get("resource_id")),
        409,
        "Provider resource mismatch");
    Object sources = r.get("sources");
    require(
        sources instanceof List && ((List<?>) sources).size() <= 10000,
        409,
        "Provider source history invalid");
    for (var source : (List<Map<String, Object>>) sources) {
      require(
          source.get("source_id") instanceof String
              && source.get("event_type") instanceof String
              && source.get("amount_minor") instanceof Number,
          409,
          "Provider source schema mismatch");
      UUID.fromString(source.get("resource_id").toString());
      Instant.parse(source.get("occurred_at").toString());
    }
    if (op.get("kind").equals("payout") && r.get("state").equals("SUCCEEDED")) {
      var payload = CanonicalJson.parse(op.get("payload").toString());
      var payoutSources =
          ((List<Map<String, Object>>) sources)
              .stream().filter(x -> x.get("event_type").equals("PAYOUT")).toList();
      require(
          payoutSources.size() == 1
              && Math.negateExact(
                      ((Number) payoutSources.getFirst().get("amount_minor")).longValue())
                  == ((Number) payload.get("amount_minor")).longValue(),
          409,
          "Payout result amount mismatch");
      if (payload.containsKey("beneficiary"))
        require(
            payload.get("beneficiary").equals(r.get("beneficiary")),
            409,
            "Payout beneficiary mismatch");
    }
  }

  @SuppressWarnings("unchecked")
  private void complete(Map<String, Object> claim, Map<String, Object> result) {
    UUID merchant = Db.uuid(claim, "merchant_id");
    db.lockMerchant(merchant);
    db.one("select * from system_control where id=true for update");
    if (!fenced(claim)) return;
    var op = db.one("select * from operation where id=? for update", claim.get("id"));
    List<Map<String, Object>> resultSources = (List<Map<String, Object>>) result.get("sources");
    if (Boolean.TRUE.equals(claim.get("history_preposted")))
      resultSources =
          resultSources.stream()
              .filter(
                  source ->
                      Set.of("SETTLEMENT", "PAYOUT", "BATCH_ZERO")
                          .contains(source.get("event_type")))
              .toList();
    ledger.apply(merchant, Db.uuid(op, "id"), resultSources);
    payments.applyResult(merchant, op, result);
    workflows.applyProviderResult(
        merchant, op.get("kind").toString(), Db.uuid(op, "resource_id"), result);
    boolean success = result.get("state").equals("SUCCEEDED");
    db.exec(
        "update operation set status=?,safe_result=?,reason=?,updated_at=now(),version=version+1"
            + " where id=?",
        success ? "SUCCEEDED" : "FAILED",
        CanonicalJson.string(result),
        success ? "Provider result applied" : "Provider confirmed no effect",
        op.get("id"));
    db.exec("update durable_job set state='DONE',lease_until=null where id=?", claim.get("job_id"));
    db.exec("update system_control set unfinished_jobs=unfinished_jobs-1 where id=true");
    audit.record(
        merchant, null, "operation.completed", Db.uuid(op, "id"), result.get("state").toString());
    events.emit(
        merchant,
        "domain-events.v1",
        merchant,
        "OPERATION_COMPLETED",
        Map.of("operation_id", op.get("id").toString()));
  }
}
