package com.hassankarkaba.mprp.reconciliation;

import static com.hassankarkaba.mprp.operations.DomainException.require;
import static com.hassankarkaba.mprp.settlements.WorkflowSupport.*;

import com.hassankarkaba.mprp.accounting.LedgerService;
import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.*;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CorrectionService {
  private final Db db;
  private final Readiness readiness;
  private final Authz auth;
  private final LedgerService ledger;
  private final com.hassankarkaba.mprp.settlements.WorkflowNotice audit;

  public CorrectionService(
      Db db,
      Authz auth,
      LedgerService ledger,
      com.hassankarkaba.mprp.settlements.WorkflowNotice audit,
      Readiness readiness) {
    this.db = db;
    this.auth = auth;
    this.ledger = ledger;
    this.audit = audit;
    this.readiness = readiness;
  }

  @Transactional
  public UUID propose(UUID merchant, UUID source, String rationale) {
    UUID actor = auth.platform("RECONCILIATION");
    rationale = text(rationale, 2000);
    db.lockMerchant(merchant);
    var s =
        db.one(
            "select * from economic_source where id=? and merchant_id=? for update",
            source,
            merchant);
    require(
        Set.of("CORRECTION_CREDIT", "CORRECTION_DEBIT").contains(s(s, "event_type"))
            && !Boolean.TRUE.equals(s.get("recognized")),
        409,
        "Select an authenticated unrecognized correction");
    require(
        Math.abs(n(s, "amount_minor")) >= 1 && Math.abs(n(s, "amount_minor")) <= 100000000,
        400,
        "Correction amount exceeds recognition bounds");
    LocalDate month = ((java.sql.Date) s.get("posting_month")).toLocalDate();
    require(
        "OPEN"
            .equals(
                db.scalar(
                    "select status from accounting_period where merchant_id=? and month=?",
                    String.class,
                    merchant,
                    month)),
        409,
        "Correction posting month must be open");
    String hash =
        CanonicalJson.sha256(
            map(
                "merchant_id",
                merchant.toString(),
                "source_id",
                source.toString(),
                "event_type",
                s(s, "event_type"),
                "amount_minor",
                n(s, "amount_minor"),
                "month",
                month.toString(),
                "rationale",
                rationale));
    var old = db.maybe("select * from correction_proposal where source_id=?", source);
    if (old.isPresent()) {
      require(
          s(old.get(), "payload_hash").equals(hash),
          409,
          "Source already has a different recognition proposal");
      return id(old.get(), "id");
    }
    UUID id = UUID.randomUUID();
    db.exec(
        "insert into"
            + " correction_proposal(id,merchant_id,source_id,maker,original_maker,amount_minor,posting_month,rationale,payload_hash)"
            + " values(?,?,?,?,?,?,?,?,?)",
        id,
        merchant,
        source,
        actor,
        actor,
        n(s, "amount_minor"),
        month,
        rationale,
        hash);
    retainHistory(id);
    audit.record(merchant, actor, "CORRECTION_PROPOSED", id, rationale);
    return id;
  }

  @Transactional
  public UUID decide(UUID proposal, long expected, String decision, String key) {
    UUID actor = auth.actorForLocking();
    var initial = db.one("select * from correction_proposal where id=?", proposal);
    lockActors(db, actor, id(initial, "maker"));
    auth.platform("FINANCE_APPROVER");
    require(Set.of("approve", "reject").contains(decision), 400, "Invalid correction decision");
    UUID merchant = id(initial, "merchant_id");
    var payload =
        map("version", expected, "decision", decision, "proposal_hash", s(initial, "payload_hash"));
    db.lockMerchant(merchant);
    var replay = receipt(db, merchant, actor, "CORRECTION_DECIDE", proposal, key, payload);
    if (replay.isPresent()) return replay.get();
    auth.stepUp();
    var p = db.one("select * from correction_proposal where id=? for update", proposal);
    version(p, expected);
    require(s(p, "state").equals("PROPOSED"), 409, "Correction has already been decided");
    require(!actor.equals(id(p, "maker")), 403, "A different finance approver must decide");
    platform(db, id(p, "maker"), "RECONCILIATION");
    platform(db, actor, "FINANCE_APPROVER");
    var s =
        db.one(
            "select * from economic_source where id=? and merchant_id=? for update",
            id(p, "source_id"),
            merchant);
    String month = p.get("posting_month").toString();
    String hash =
        CanonicalJson.sha256(
            map(
                "merchant_id",
                merchant.toString(),
                "source_id",
                s(p, "source_id"),
                "event_type",
                s(s, "event_type"),
                "amount_minor",
                n(s, "amount_minor"),
                "month",
                month,
                "rationale",
                s(p, "rationale")));
    require(hash.equals(s(p, "payload_hash")), 409, "Correction proposal integrity failed");
    require(
        "OPEN"
            .equals(
                db.scalar(
                    "select status from accounting_period where merchant_id=? and month=?",
                    String.class,
                    merchant,
                    p.get("posting_month"))),
        409,
        "Correction period is no longer open");
    if (decision.equals("approve")) {
      db.one("select id from system_control where id=true for update");
      require(
          Boolean.TRUE.equals(readiness.capabilities().get("financial_admission")),
          503,
          "Financial recognition is held or unready");
      ledger.recognize(merchant, id(p, "source_id"));
      db.exec(
          "update reconciliation_exception set state='RESOLVED',reason='Independent correction"
              + " recognition approved',resolved_at=now(),version=version+1 where merchant_id=? and"
              + " category='UNRECOGNIZED_CORRECTION' and source_reference=?",
          merchant,
          s(p, "source_id"));
    }
    db.exec(
        "update correction_proposal set checker=?,state=?,decided_at=now(),version=version+1 where"
            + " id=?",
        actor,
        decision.equals("approve") ? "APPROVED" : "REJECTED",
        proposal);
    retainHistory(proposal);
    saveReceipt(db, merchant, actor, "CORRECTION_DECIDE", proposal, key, payload, proposal);
    audit.record(
        merchant,
        actor,
        "CORRECTION_" + decision.toUpperCase(Locale.ROOT),
        proposal,
        s(p, "rationale"));
    return proposal;
  }

  @Transactional
  public UUID adopt(UUID proposal, long expected, String key) {
    UUID actor = auth.actorForLocking();
    var initial = db.one("select * from correction_proposal where id=?", proposal);
    lockActors(db, actor, id(initial, "maker"));
    auth.platform("RECONCILIATION");
    UUID merchant = id(initial, "merchant_id");
    db.lockMerchant(merchant);
    var payload =
        map("version", expected, "decision", "adopt", "proposal_hash", s(initial, "payload_hash"));
    var replay = receipt(db, merchant, actor, "CORRECTION_ADOPT", proposal, key, payload);
    if (replay.isPresent()) return replay.get();
    auth.stepUp();
    var p = db.one("select * from correction_proposal where id=? for update", proposal);
    version(p, expected);
    require(s(p, "state").equals("PROPOSED"), 409, "Only a pending correction can be adopted");
    require(
        db.scalar(
                "select count(*) from app_user where id=? and active and verified and"
                    + " totp_confirmed and platform_role='RECONCILIATION'",
                Long.class,
                id(p, "maker"))
            == 0,
        409,
        "Current maker still has reconciliation authority");
    var source =
        db.one(
            "select * from economic_source where id=? and merchant_id=?",
            id(p, "source_id"),
            merchant);
    String hash =
        CanonicalJson.sha256(
            map(
                "merchant_id",
                merchant.toString(),
                "source_id",
                s(p, "source_id"),
                "event_type",
                s(source, "event_type"),
                "amount_minor",
                n(source, "amount_minor"),
                "month",
                p.get("posting_month").toString(),
                "rationale",
                s(p, "rationale")));
    require(hash.equals(s(p, "payload_hash")), 409, "Frozen correction integrity failed");
    retainHistory(proposal);
    db.exec("update correction_proposal set maker=?,version=version+1 where id=?", actor, proposal);
    retainHistory(proposal);
    saveReceipt(db, merchant, actor, "CORRECTION_ADOPT", proposal, key, payload, proposal);
    audit.record(
        merchant,
        actor,
        "CORRECTION_ADOPTED",
        proposal,
        "Identical frozen proposal adopted after maker authority was lost");
    return proposal;
  }

  private void retainHistory(UUID proposal) {
    var p = db.one("select * from correction_proposal where id=?", proposal);
    var snapshot =
        map(
            "proposal_id",
            proposal.toString(),
            "merchant_id",
            s(p, "merchant_id"),
            "source_id",
            s(p, "source_id"),
            "maker",
            s(p, "maker"),
            "original_maker",
            s(p, "original_maker"),
            "checker",
            p.get("checker") == null ? null : s(p, "checker"),
            "amount_minor",
            n(p, "amount_minor"),
            "posting_month",
            p.get("posting_month").toString(),
            "rationale",
            s(p, "rationale"),
            "payload_hash",
            s(p, "payload_hash"),
            "state",
            s(p, "state"),
            "version",
            n(p, "version"));
    db.exec(
        "insert into"
            + " correction_proposal_history(id,proposal_id,merchant_id,proposal_version,state,maker,checker,snapshot_json)"
            + " values(?,?,?,?,?,?,?,?) on conflict(proposal_id,proposal_version) do nothing",
        UUID.randomUUID(),
        proposal,
        id(p, "merchant_id"),
        n(p, "version"),
        s(p, "state"),
        id(p, "maker"),
        p.get("checker"),
        CanonicalJson.string(snapshot));
  }
}
