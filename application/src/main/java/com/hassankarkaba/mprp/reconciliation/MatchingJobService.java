package com.hassankarkaba.mprp.reconciliation;

import static com.hassankarkaba.mprp.operations.DomainException.require;
import static com.hassankarkaba.mprp.settlements.WorkflowSupport.*;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.*;
import com.hassankarkaba.mprp.settlements.WorkflowNotice;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingJobService {
  private final Db db;
  private final Authz auth;
  private final Events events;
  private final WorkflowNotice audit;
  private final Readiness readiness;

  public MatchingJobService(
      Db db, Authz auth, Events events, WorkflowNotice audit, Readiness readiness) {
    this.db = db;
    this.auth = auth;
    this.events = events;
    this.audit = audit;
    this.readiness = readiness;
  }

  @Transactional
  public UUID admit(UUID merchant) {
    UUID actor = auth.platform("RECONCILIATION");
    db.lockMerchant(merchant);
    var retained =
        db.maybe(
            "select id from matching_run where merchant_id=? and status in('QUEUED','RUNNING')",
            merchant);
    if (retained.isPresent()) return id(retained.get(), "id");
    require(
        Boolean.TRUE.equals(readiness.capabilities().get("reconciliation")),
        503,
        "Reconciliation capability is not ready");
    var control = db.one("select * from system_control where id=true for update");
    require(
        !time(control.get("clock_at")).isAfter(java.time.Instant.now().plusSeconds(2)),
        503,
        "Clock safety hold blocks new matching");
    require(
        n(control, "unfinished_jobs") < 10000 && n(control, "unpublished_events") <= 99998,
        503,
        "Matching admission capacity is full");
    UUID id = UUID.randomUUID();
    db.exec("insert into matching_run(id,merchant_id,actor_id) values(?,?,?)", id, merchant, actor);
    db.exec(
        "insert into durable_job(id,kind,resource_id,merchant_id) values(?,'MATCHING',?,?)",
        UUID.randomUUID(),
        id,
        merchant);
    db.exec("update system_control set unfinished_jobs=unfinished_jobs+1 where id=true");
    events.emit(
        merchant,
        "work.v1",
        merchant,
        "MATCHING_ACCEPTED",
        Map.of("matching_run_id", id.toString()));
    audit.record(
        merchant,
        actor,
        "MATCHING_ADMITTED",
        id,
        "Durable exact matching; refreshing preserves the active run");
    return id;
  }
}
