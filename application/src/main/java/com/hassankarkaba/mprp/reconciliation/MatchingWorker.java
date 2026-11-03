package com.hassankarkaba.mprp.reconciliation;

import static com.hassankarkaba.mprp.operations.DomainException.require;
import static com.hassankarkaba.mprp.settlements.WorkflowSupport.*;

import com.hassankarkaba.mprp.operations.*;
import com.hassankarkaba.mprp.settlements.WorkflowNotice;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MatchingWorker {
  private final Db db;
  private final MatchService matches;
  private final TransactionTemplate tx;
  private final boolean enabled;
  private final WorkflowNotice audit;
  private final UUID owner = UUID.randomUUID();

  public MatchingWorker(
      Db db,
      MatchService matches,
      PlatformTransactionManager manager,
      WorkflowNotice audit,
      @Value("${mprp.worker:false}") boolean enabled) {
    this.db = db;
    this.matches = matches;
    this.tx = new TransactionTemplate(manager);
    this.audit = audit;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 1000)
  public void discover() {
    if (!enabled) return;
    for (var candidate :
        db.query(
            "select j.id,j.merchant_id,r.actor_id from durable_job j join matching_run r on"
                + " r.id=j.resource_id where j.kind='MATCHING' and ((j.state='READY' and"
                + " j.due_at<=now()) or (j.state='RUNNING' and j.lease_until<clock_timestamp()))"
                + " order by j.due_at,j.id limit 5")) {
      Map<String, Object> claim = tx.execute(status -> claim(candidate));
      if (claim != null) execute(claim);
    }
  }

  private Map<String, Object> claim(Map<String, Object> candidate) {
    UUID actor = id(candidate, "actor_id"), merchant = id(candidate, "merchant_id");
    lockActors(db, actor);
    db.lockMerchant(merchant);
    var control = db.one("select * from system_control where id=true for update");
    if (Boolean.TRUE.equals(control.get("restore_hold"))
        || time(control.get("clock_at")).isAfter(java.time.Instant.now().plusSeconds(2)))
      return null;
    var maybe =
        db.maybe(
            "select * from durable_job where id=? and ((state='READY' and due_at<=now()) or"
                + " (state='RUNNING' and lease_until<clock_timestamp())) for update skip locked",
            id(candidate, "id"));
    if (maybe.isEmpty()) return null;
    var job = maybe.get();
    var run = db.one("select * from matching_run where id=? for update", id(job, "resource_id"));
    try {
      platform(db, actor, "RECONCILIATION");
    } catch (DomainException revoked) {
      db.exec(
          "update matching_run set status='FAILED',safe_message='Current reconciliation authority"
              + " was revoked',updated_at=now() where id=?",
          id(run, "id"));
      db.exec("update durable_job set state='DONE' where id=?", id(job, "id"));
      db.exec("update system_control set unfinished_jobs=unfinished_jobs-1 where id=true");
      audit.recordAccepted(
          merchant,
          null,
          "MATCHING_AUTHORITY_REVOKED",
          id(run, "id"),
          "Retained matching ended before new evidence mutation");
      return null;
    }
    long generation = n(job, "generation") + 1;
    db.exec(
        "update durable_job set"
            + " state='RUNNING',owner=?,generation=?,lease_until=clock_timestamp()+interval '30"
            + " seconds',run_id=?,dispatch_epoch=?,attempts=attempts+1 where id=?",
        owner,
        generation,
        control.get("run_id"),
        control.get("dispatch_epoch"),
        id(job, "id"));
    db.exec(
        "update matching_run set status='RUNNING',safe_message='Processing bounded evidence"
            + " chunk',updated_at=now() where id=?",
        id(run, "id"));
    var result = new HashMap<String, Object>(run);
    result.put("job_id", id(job, "id"));
    result.put("generation", generation);
    result.put("run_id", control.get("run_id"));
    result.put("dispatch_epoch", control.get("dispatch_epoch"));
    return result;
  }

  private boolean fenced(Map<String, Object> claim) {
    return db.scalar(
            "select count(*) from durable_job j join system_control c on c.id=true where j.id=? and"
                + " j.owner=? and j.generation=? and j.state='RUNNING' and"
                + " j.lease_until>clock_timestamp() and j.run_id=c.run_id and"
                + " j.dispatch_epoch=c.dispatch_epoch and not c.restore_hold and"
                + " c.clock_at<=clock_timestamp()+interval '2 seconds'",
            Long.class,
            claim.get("job_id"),
            owner,
            claim.get("generation"))
        == 1;
  }

  private void execute(Map<String, Object> claim) {
    UUID merchant = id(claim, "merchant_id"), actor = id(claim, "actor_id"), run = id(claim, "id");
    try {
      List<Map<String, Object>> lines =
          claim.get("cursor_time") == null
              ? db.query(
                  "select * from statement_line where merchant_id=? and created_at<=? order by"
                      + " occurred_at,id limit 500",
                  merchant,
                  claim.get("cutoff"))
              : db.query(
                  "select * from statement_line where merchant_id=? and created_at<=? and"
                      + " (occurred_at,id)>(?,?) order by occurred_at,id limit 500",
                  merchant,
                  claim.get("cutoff"),
                  claim.get("cursor_time"),
                  claim.get("cursor_id"));
      tx.executeWithoutResult(
          status -> {
            lockActors(db, actor);
            platform(db, actor, "RECONCILIATION");
            db.lockMerchant(merchant);
            db.one("select id from system_control where id=true for update");
            require(fenced(claim), 409, "Matching lease is stale");
            var result = matches.autoMatchBatch(merchant, actor, lines, () -> fenced(claim));
            boolean done = result.examined() == lines.size() && lines.size() < 500;
            Map<String, Object> last = result.last();
            if (last != null)
              db.exec(
                  "update matching_run set"
                      + " cursor_time=?,cursor_id=?,matched_groups=matched_groups+?,examined_lines=examined_lines+?,status=?,safe_message=?,updated_at=now()"
                      + " where id=?",
                  last.get("occurred_at"),
                  last.get("id"),
                  result.matched(),
                  result.examined(),
                  done ? "SUCCEEDED" : "RUNNING",
                  done
                      ? "Exact matching completed; inspect remaining exceptions"
                      : "Chunk committed; more evidence remains",
                  run);
            else
              db.exec(
                  "update matching_run set status='SUCCEEDED',safe_message='Exact matching"
                      + " completed; inspect remaining exceptions',updated_at=now() where id=?",
                  run);
            if (done || result.examined() > 0)
              audit.recordAccepted(
                  merchant,
                  actor,
                  done ? "MATCHING_COMPLETED" : "MATCHING_CHUNK_APPLIED",
                  run,
                  "Examined "
                      + result.examined()
                      + " lines and matched "
                      + result.matched()
                      + " exact groups in this committed chunk");

            require(fenced(claim), 409, "Matching lease expired before commitment");
            int changed =
                db.exec(
                    "update durable_job set state=?,lease_until=null,due_at=now() where id=? and"
                        + " owner=? and generation=? and lease_until>clock_timestamp() and"
                        + " state='RUNNING'",
                    done ? "DONE" : "READY",
                    claim.get("job_id"),
                    owner,
                    claim.get("generation"));
            require(changed == 1, 409, "Matching completion is fenced");
            if (done)
              db.exec("update system_control set unfinished_jobs=unfinished_jobs-1 where id=true");
          });
    } catch (Exception failure) {
      tx.executeWithoutResult(
          status -> {
            if (!fenced(claim)) return;
            db.exec(
                "update matching_run set safe_message='Batch retained for retry; inspect exceptions"
                    + " or current authority',updated_at=now() where id=?",
                run);
            int attempt =
                db.scalar(
                    "select attempts from durable_job where id=?",
                    Integer.class,
                    claim.get("job_id"));
            int delay = new int[] {1, 5, 30, 120}[Math.min(Math.max(attempt - 1, 0), 3)];
            db.exec(
                "update durable_job set state='READY',lease_until=null,due_at=now()+(? * interval"
                    + " '1 second'),last_error='MATCH_RETRY' where id=? and owner=? and"
                    + " generation=?",
                delay,
                claim.get("job_id"),
                owner,
                claim.get("generation"));
          });
    }
  }
}
