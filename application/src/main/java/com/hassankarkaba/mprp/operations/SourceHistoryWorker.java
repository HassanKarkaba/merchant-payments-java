package com.hassankarkaba.mprp.operations;

import com.hassankarkaba.mprp.accounting.LedgerService;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Accepted system-event histories never enter the monetary operation resolver/key namespace. */
@Component
public class SourceHistoryWorker {
  private final Db db;
  private final TransactionTemplate tx;
  private final ProviderHistoryService histories;
  private final LedgerService ledger;
  private final CallbackService callbacks;
  private final Events events;
  private final UUID owner = UUID.randomUUID();
  private final boolean enabled;

  public SourceHistoryWorker(
      Db db,
      PlatformTransactionManager manager,
      ProviderHistoryService histories,
      LedgerService ledger,
      CallbackService callbacks,
      Events events,
      @Value("${mprp.worker:false}") boolean enabled) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.histories = histories;
    this.ledger = ledger;
    this.callbacks = callbacks;
    this.events = events;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 1000)
  public void discover() {
    if (!enabled) return;
    try {
      for (var row :
          db.query(
              "select i.id from callback_inbox i where i.state='ACCEPTED' and"
                  + " i.payload::jsonb#>>'{result,history_required}'='true' and"
                  + " ((i.payload::jsonb->>'event_type'"
                  + " in('settlement_completed','dispute_outcome') and not exists(select 1 from"
                  + " operation o where"
                  + " o.provider_key=i.payload::jsonb#>>'{result,operation_key}')) or exists(select"
                  + " 1 from operation o where"
                  + " o.provider_key=i.payload::jsonb#>>'{result,operation_key}' and"
                  + " o.status='SUCCEEDED')) and not exists(select 1 from durable_job j where"
                  + " j.kind='PROVIDER_HISTORY' and j.resource_id=i.id) order by i.created_at,i.id"
                  + " limit 20"))
        admit(Db.uuid(row, "id"));
      // Finish independently committed inbox truth even if its original job lease/owner was lost.
      for (var row :
          db.query(
              "select j.id,j.merchant_id from durable_job j join callback_inbox i on"
                  + " i.id=j.resource_id where j.kind='PROVIDER_HISTORY' and j.state<>'DONE' and"
                  + " i.state='APPLIED' order by j.created_at limit 20"))
        settleApplied(Db.uuid(row, "id"), Db.uuid(row, "merchant_id"));
      var due =
          db.maybe(
              "select id,merchant_id from durable_job where kind='PROVIDER_HISTORY' and ((state"
                  + " in('READY','AWAITING_CALLBACK') and due_at<=clock_timestamp())"
                  + " or(state='RUNNING' and lease_until<clock_timestamp())) order by due_at,id"
                  + " limit 1");
      if (due.isEmpty()) return;
      Map<String, Object> claim =
          tx.execute(s -> claim(Db.uuid(due.get(), "id"), Db.uuid(due.get(), "merchant_id")));
      if (claim == null) return;
      run(claim);
    } catch (RuntimeException unavailable) {
      /* Accepted inbox/jobs remain durable and inspectable. */
    }
  }

  @SuppressWarnings("unchecked")
  private void admit(UUID inbox) {
    tx.executeWithoutResult(
        s -> {
          var event =
              CanonicalJson.parse(
                  db.one("select payload from callback_inbox where id=?", inbox)
                      .get("payload")
                      .toString());
          UUID merchant = UUID.fromString(event.get("provider_account_id").toString());
          db.lockMerchant(merchant);
          db.one("select id from system_control where id=true for update");
          int added =
              db.exec(
                  "insert into durable_job(id,kind,resource_id,merchant_id) values"
                      + " (?,'PROVIDER_HISTORY',?,?) on conflict do nothing",
                  UUID.randomUUID(),
                  inbox,
                  merchant);
          if (added == 1)
            db.exec("update system_control set unfinished_jobs=unfinished_jobs+1 where id=true");
        });
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> claim(UUID job, UUID merchant) {
    db.lockMerchant(merchant);
    var control = db.one("select * from system_control where id=true for update");
    var found =
        db.maybe(
            "select * from durable_job where id=? and kind='PROVIDER_HISTORY' and ((state"
                + " in('READY','AWAITING_CALLBACK') and due_at<=clock_timestamp())"
                + " or(state='RUNNING' and lease_until<clock_timestamp())) for update skip locked",
            job);
    if (found.isEmpty()) return null;
    var row = found.get();
    var inbox = db.one("select * from callback_inbox where id=?", row.get("resource_id"));
    var event = CanonicalJson.parse(inbox.get("payload").toString());
    if (!(event.get("result") instanceof Map<?, ?>)) {
      invalidClaim(job, merchant, event, "Authenticated history event has no result object");
      return null;
    }
    var result = (Map<String, Object>) event.get("result");
    var retained =
        db.maybe(
            "select * from operation where provider_key=? and merchant_id=?",
            result.get("operation_key"),
            merchant);
    if (inbox.get("state").equals("APPLIED")
        || (retained.isPresent() && !retained.get().get("status").equals("SUCCEEDED"))) {
      db.exec(
          "update durable_job set state='DONE',generation=generation+1,lease_until=null where id=?",
          job);
      db.exec("update system_control set unfinished_jobs=unfinished_jobs-1 where id=true");
      return null;
    }
    if (retained.isEmpty()
        && !Set.of("settlement_completed", "dispute_outcome").contains(event.get("event_type"))) {
      invalidClaim(
          job,
          merchant,
          event,
          "System history has no retained admission or supported effect authority");
      return null;
    }
    long generation = Db.number(row, "generation") + 1;
    db.exec(
        "update durable_job set"
            + " state='RUNNING',owner=?,generation=?,run_id=?,dispatch_epoch=?,lease_until=clock_timestamp()+interval"
            + " '30 seconds',attempts=attempts+1 where id=?",
        owner,
        generation,
        control.get("run_id"),
        control.get("dispatch_epoch"),
        job);
    Map<String, Object> claim = new HashMap<>();
    claim.put("id", inbox.get("id"));
    claim.put("history_inbox_id", inbox.get("id"));
    claim.put("job_id", job);
    claim.put("merchant_id", merchant);
    claim.put("owner", owner);
    claim.put("generation", generation);
    claim.put("run_id", control.get("run_id"));
    claim.put("epoch", control.get("dispatch_epoch"));
    claim.put("provider_key", result.get("operation_key"));
    claim.put("fingerprint", result.get("payload_hash"));
    claim.put("resource_id", UUID.fromString(result.get("resource_id").toString()));
    claim.put("kind", "system_history");
    claim.put("summary", result);
    return claim;
  }

  @SuppressWarnings("unchecked")
  private void run(Map<String, Object> claim) {
    UUID inbox = Db.uuid(claim, "id"), merchant = Db.uuid(claim, "merchant_id");
    try {
      var expanded = histories.expand(claim, (Map<String, Object>) claim.get("summary"));
      if (expanded.isEmpty()) {
        release(claim, histories.manualInbox(inbox), histories.messageInbox(inbox));
        return;
      }
      if (!histories.postChunk(claim, expanded.get(), ledger)) {
        release(
            claim, false, "Authenticated system history journals retained; continuation pending");
        return;
      }
      Boolean handed =
          tx.execute(
              s -> {
                if (!histories.isCurrentClaim(claim)) return false;
                db.exec(
                    "update durable_job set"
                        + " state='AWAITING_CALLBACK',lease_until=null,due_at=clock_timestamp(),last_error='Verified"
                        + " system history ready for atomic callback finalization' where id=?",
                    claim.get("job_id"));
                return true;
              });
      if (!Boolean.TRUE.equals(handed)) return;
      callbacks.apply(inbox);
      settleApplied(Db.uuid(claim, "job_id"), merchant);
    } catch (RuntimeException failure) {
      retryFailure(claim);
    }
  }

  private void retryFailure(Map<String, Object> claim) {
    tx.executeWithoutResult(
        s -> {
          // RUNNING failures retain the old lease's deferred commit guard. A handoff has
          // relinquished that lease, so only its still-current owner/generation may retry it.
          boolean running = histories.isCurrentClaim(claim), awaiting = false;
          if (!running) {
            awaiting =
                db.maybe(
                        "select j.id from durable_job j join system_control c on c.id=true where"
                            + " j.id=? and j.kind='PROVIDER_HISTORY' and j.resource_id=? and"
                            + " j.merchant_id=? and j.state='AWAITING_CALLBACK' and j.owner=? and"
                            + " j.generation=? and j.run_id=? and j.dispatch_epoch=? and"
                            + " j.run_id=c.run_id and j.dispatch_epoch=c.dispatch_epoch and not"
                            + " exists(select 1 from callback_inbox i where i.id=j.resource_id and"
                            + " i.state='APPLIED') for update of j",
                        claim.get("job_id"),
                        claim.get("id"),
                        claim.get("merchant_id"),
                        claim.get("owner"),
                        claim.get("generation"),
                        claim.get("run_id"),
                        claim.get("epoch"))
                    .isPresent();
            if (!awaiting) return;
          }
          long attempt =
              db.scalar(
                  "select attempts from durable_job where id=?", Long.class, claim.get("job_id"));
          boolean manual =
              db.scalar(
                  "select created_at<clock_timestamp()-interval '24 hours' from durable_job where"
                      + " id=?",
                  Boolean.class,
                  claim.get("job_id"));
          int delay = new int[] {1, 5, 30, 120}[(int) Math.min(Math.max(attempt - 1, 0), 3)];
          String reason =
              "Authenticated system history retained; provider or local processing requires retry";
          if (awaiting)
            db.exec(
                "update durable_job set"
                    + " state=?,generation=generation+1,owner=null,lease_until=null,due_at=clock_timestamp()+(?*interval"
                    + " '1 second'),last_error=? where id=?",
                manual ? "MANUAL" : "READY",
                delay,
                reason,
                claim.get("job_id"));
          else
            db.exec(
                "update durable_job set"
                    + " state=?,lease_until=null,due_at=clock_timestamp()+(?*interval '1"
                    + " second'),last_error=? where id=?",
                manual ? "MANUAL" : "READY",
                delay,
                reason,
                claim.get("job_id"));
          if (manual) attention(claim, reason);
        });
  }

  private void release(Map<String, Object> claim, boolean manual, String reason) {
    tx.executeWithoutResult(
        s -> {
          if (!histories.isCurrentClaim(claim)) return;
          db.exec(
              "update durable_job set state=?,lease_until=null,due_at=clock_timestamp()+interval '1"
                  + " second',last_error=? where id=?",
              manual ? "MANUAL" : "READY",
              reason,
              claim.get("job_id"));
          if (manual) attention(claim, reason);
        });
  }

  private void attention(Map<String, Object> claim, String reason) {
    UUID id = UUID.randomUUID();
    db.exec(
        "insert into quarantine(id,kind,digest,safe_reason) values (?,'SYSTEM_HISTORY',?,?)",
        id,
        CanonicalJson.sha256(claim.get("summary")),
        "Inbox " + claim.get("id") + ": " + reason);
    events.emit(
        Db.uuid(claim, "merchant_id"),
        "failures.v1",
        Db.uuid(claim, "id"),
        "PROVIDER_HISTORY_REQUIRES_ATTENTION",
        Map.of(
            "quarantine_id",
            id.toString(),
            "inbox_id",
            claim.get("id").toString(),
            "job_id",
            claim.get("job_id").toString()));
  }

  private void invalidClaim(UUID job, UUID merchant, Map<String, Object> event, String reason) {
    db.exec(
        "update durable_job set"
            + " state='MANUAL',generation=generation+1,lease_until=null,last_error=? where id=?",
        reason,
        job);
    UUID id = UUID.randomUUID();
    db.exec(
        "insert into quarantine(id,kind,digest,safe_reason) values (?,'SYSTEM_HISTORY',?,?)",
        id,
        CanonicalJson.sha256(event),
        reason);
    events.emit(
        merchant,
        "failures.v1",
        job,
        "PROVIDER_HISTORY_REQUIRES_ATTENTION",
        Map.of("quarantine_id", id.toString(), "job_id", job.toString()));
  }

  private void settleApplied(UUID job, UUID merchant) {
    tx.executeWithoutResult(
        s -> {
          db.lockMerchant(merchant);
          db.one("select id from system_control where id=true for update");
          var row = db.one("select j.* from durable_job j where j.id=? for update", job);
          if (row.get("state").equals("DONE")) return;
          if (db.scalar(
                  "select count(*) from callback_inbox where id=? and state='APPLIED'",
                  Long.class,
                  row.get("resource_id"))
              != 1) return;
          db.exec(
              "update durable_job set state='DONE',generation=generation+1,lease_until=null where"
                  + " id=?",
              job);
          db.exec("update system_control set unfinished_jobs=unfinished_jobs-1 where id=true");
        });
  }
}
