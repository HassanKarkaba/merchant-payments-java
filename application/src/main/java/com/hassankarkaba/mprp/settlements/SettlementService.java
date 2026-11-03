package com.hassankarkaba.mprp.settlements;

import static com.hassankarkaba.mprp.operations.DomainException.require;
import static com.hassankarkaba.mprp.settlements.WorkflowSupport.*;

import com.hassankarkaba.mprp.accounting.LedgerService;
import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.*;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {
  private final Db db;
  private final Authz auth;
  private final LedgerService ledger;
  private final OperationService operations;
  private final WorkflowNotice audit;

  public SettlementService(
      Db db, Authz auth, LedgerService ledger, OperationService operations, WorkflowNotice audit) {
    this.db = db;
    this.auth = auth;
    this.ledger = ledger;
    this.operations = operations;
    this.audit = audit;
  }

  @Transactional
  public UUID proposeBeneficiary(UUID merchant, String token) {
    UUID actor = auth.require(merchant, "OWNER", "FINANCE");
    db.lockMerchant(merchant);
    member(db, actor, merchant, "OWNER", "FINANCE");
    activeMerchant(db, merchant);
    require(
        token != null && token.matches("acct_demo_[0-9a-f]{32}"),
        400,
        "Use a simulator-issued beneficiary identifier");
    UUID id = UUID.randomUUID();
    String hash = CanonicalJson.sha256(map("merchant_id", merchant.toString(), "token", token));
    db.exec(
        "insert into beneficiary(id,merchant_id,maker,token,payload_hash) values(?,?,?,?,?)",
        id,
        merchant,
        actor,
        token,
        hash);
    audit.record(merchant, actor, "BENEFICIARY_PROPOSED", id, "Synthetic destination proposal");
    return id;
  }

  @Transactional
  public UUID approveBeneficiary(UUID merchant, UUID proposal, long expected, String key) {
    UUID actor = auth.actorForLocking();
    var maker =
        db.one("select maker from beneficiary where merchant_id=? and id=?", merchant, proposal);
    lockActors(db, actor, id(maker, "maker"));
    auth.require(merchant, "OWNER");
    var payload = map("version", expected, "decision", "approve");
    db.lockMerchant(merchant);
    var replay = receipt(db, merchant, actor, "BENEFICIARY_APPROVE", proposal, key, payload);
    if (replay.isPresent()) return replay.get();
    auth.stepUp();
    var b =
        db.one(
            "select * from beneficiary where id=? and merchant_id=? for update",
            proposal,
            merchant);
    version(b, expected);
    require("PROPOSED".equals(s(b, "status")), 409, "Beneficiary is not awaiting approval");
    require(!actor.equals(id(b, "maker")), 403, "A different owner must approve this beneficiary");
    member(db, id(b, "maker"), merchant, "OWNER", "FINANCE");
    member(db, actor, merchant, "OWNER");
    activeMerchant(db, merchant);
    require(
        CanonicalJson.sha256(map("merchant_id", merchant.toString(), "token", s(b, "token")))
            .equals(s(b, "payload_hash")),
        409,
        "Proposal integrity check failed");
    db.exec(
        "update beneficiary set status='SUPERSEDED',version=version+1 where merchant_id=? and"
            + " status='ACTIVE'",
        merchant);
    for (var p :
        db.query(
            "select p.* from payout p join operation o on o.id=p.operation_id where p.merchant_id=?"
                + " and p.status='APPROVED' and o.status='QUEUED' for update of p,o",
            merchant)) {
      operations.cancel(merchant, id(p, "operation_id"), actor);
      db.exec(
          "update payout set"
              + " status='NEEDS_REAPPROVAL',reserved=false,version=version+1,updated_at=now() where"
              + " id=?",
          id(p, "id"));
    }
    db.exec(
        "update beneficiary set status='ACTIVE',checker=?,approved_at=now(),version=version+1 where"
            + " id=?",
        actor,
        proposal);
    saveReceipt(db, merchant, actor, "BENEFICIARY_APPROVE", proposal, key, payload, proposal);
    audit.record(merchant, actor, "BENEFICIARY_APPROVED", proposal, "Independent owner approval");
    return proposal;
  }

  @Transactional
  public UUID proposePayout(UUID merchant, UUID beneficiary, long amount, String reason) {
    UUID actor = auth.require(merchant, "OWNER", "FINANCE");
    require(
        amount >= 1 && amount <= 100000000, 400, "Payout must be between 1 and 100000000 cents");
    reason = text(reason, 2000);
    db.lockMerchant(merchant);
    activeMerchant(db, merchant);
    var b =
        db.one(
            "select * from beneficiary where id=? and merchant_id=? and status='ACTIVE'",
            beneficiary,
            merchant);
    UUID id = UUID.randomUUID();
    String hash =
        CanonicalJson.sha256(
            map(
                "merchant_id",
                merchant.toString(),
                "beneficiary_id",
                beneficiary.toString(),
                "beneficiary_version",
                n(b, "version"),
                "amount_minor",
                amount,
                "reason",
                reason));
    db.exec(
        "insert into"
            + " payout(id,merchant_id,maker,beneficiary_id,beneficiary_version,amount_minor,reason,payload_hash)"
            + " values(?,?,?,?,?,?,?,?)",
        id,
        merchant,
        actor,
        beneficiary,
        n(b, "version"),
        amount,
        reason,
        hash);
    freezeProposal(id);
    audit.record(merchant, actor, "PAYOUT_PROPOSED", id, reason);
    return id;
  }

  public long available(UUID merchant) {
    var b = ledger.balances(merchant);
    long payable = b.getOrDefault("merchant_payable", 0L),
        bank = b.getOrDefault("bank_control", 0L);
    long payout =
        db.scalar(
            "select coalesce(sum(amount_minor),0) from payout where merchant_id=? and reserved",
            Long.class,
            merchant);
    long refunds =
        db.scalar(
            "select coalesce(sum(reserved_refund_minor),0) from payment where merchant_id=?",
            Long.class,
            merchant);
    return AvailableFunds.cents(payable, bank, payout, refunds);
  }

  @Transactional
  public UUID approvePayout(
      UUID merchant, UUID payout, long expected, String key, String proposalHash) {
    UUID actor = auth.actorForLocking();
    var maker = db.one("select maker from payout where merchant_id=? and id=?", merchant, payout);
    lockActors(db, actor, id(maker, "maker"));
    auth.require(merchant, "OWNER");
    var payload = map("version", expected, "decision", "approve", "proposal_hash", proposalHash);
    db.lockMerchant(merchant);
    var replay = receipt(db, merchant, actor, "PAYOUT_APPROVE", payout, key, payload);
    if (replay.isPresent()) return replay.get();
    auth.stepUp();
    var p =
        db.one("select * from payout where merchant_id=? and id=? for update", merchant, payout);
    version(p, expected);
    require(s(p, "payload_hash").equals(proposalHash), 409, "Frozen proposal hash changed");
    require(
        Set.of("PROPOSED", "NEEDS_REAPPROVAL").contains(s(p, "status")),
        409,
        "Payout is not awaiting approval");
    require(!actor.equals(id(p, "maker")), 403, "A different owner must approve this payout");
    member(db, id(p, "maker"), merchant, "OWNER", "FINANCE");
    member(db, actor, merchant, "OWNER");
    activeMerchant(db, merchant);
    var b =
        db.one(
            "select * from beneficiary where id=? and merchant_id=? and status='ACTIVE'",
            id(p, "beneficiary_id"),
            merchant);
    require(
        n(b, "version") == n(p, "beneficiary_version"),
        409,
        "Beneficiary changed. Create a new proposal.");
    require(
        CanonicalJson.sha256(
                map(
                    "merchant_id",
                    merchant.toString(),
                    "beneficiary_id",
                    id(p, "beneficiary_id").toString(),
                    "beneficiary_version",
                    n(p, "beneficiary_version"),
                    "amount_minor",
                    n(p, "amount_minor"),
                    "reason",
                    s(p, "reason")))
            .equals(s(p, "payload_hash")),
        409,
        "Payout proposal integrity failed");
    noDispute(merchant);
    require(
        available(merchant) >= n(p, "amount_minor"), 409, "Insufficient available settled funds");
    UUID operation =
        operations.admit(
            merchant,
            actor,
            "payout",
            payout,
            key,
            map(
                "amount_minor",
                n(p, "amount_minor"),
                "beneficiary",
                s(b, "token"),
                "beneficiary_version",
                n(b, "version")));
    db.exec(
        "update payout set"
            + " checker=?,status='APPROVED',reserved=true,approved_at=now(),approval_expires_at=now()+interval"
            + " '24 hours',operation_id=?,version=version+1,updated_at=now() where id=?",
        actor,
        operation,
        payout);
    saveReceipt(db, merchant, actor, "PAYOUT_APPROVE", payout, key, payload, payout);
    audit.record(merchant, actor, "PAYOUT_APPROVED", payout, "Independent owner approval");
    return payout;
  }

  @Transactional
  public void cancelPayout(UUID merchant, UUID payout, long expected, String key) {
    UUID actor = auth.require(merchant, "OWNER", "FINANCE");
    var payload = map("version", expected, "decision", "cancel");
    db.lockMerchant(merchant);
    if (receipt(db, merchant, actor, "PAYOUT_CANCEL", payout, key, payload).isPresent()) return;
    var p =
        db.one("select * from payout where merchant_id=? and id=? for update", merchant, payout);
    version(p, expected);
    require(
        Set.of("PROPOSED", "NEEDS_REAPPROVAL", "APPROVED").contains(s(p, "status")),
        409,
        "Payout cannot be cancelled");
    if (p.get("operation_id") != null) operations.cancel(merchant, id(p, "operation_id"), actor);
    db.exec(
        "update payout set status='CANCELLED',reserved=false,version=version+1,updated_at=now()"
            + " where id=?",
        payout);
    saveReceipt(db, merchant, actor, "PAYOUT_CANCEL", payout, key, payload, payout);
    audit.record(merchant, actor, "PAYOUT_CANCELLED", payout, "Provably unsent or unapproved");
  }

  public void noDispute(UUID merchant) {
    require(
        db.scalar(
                "select count(*) from dispute where merchant_id=? and status"
                    + " in('OPEN','RESPONDED')",
                Long.class,
                merchant)
            == 0,
        409,
        "An open dispute blocks this payout");
    require(
        db.scalar(
                "select count(*) from economic_source where merchant_id=? and not recognized and"
                    + " event_type in('CORRECTION_CREDIT','CORRECTION_DEBIT')",
                Long.class,
                merchant)
            == 0,
        409,
        "Unrecognized correction blocks this payout");
  }

  public void validateDispatch(Map<String, Object> op) {
    UUID merchant = id(op, "merchant_id"), resource = id(op, "resource_id");
    var p =
        db.one("select * from payout where merchant_id=? and id=? for update", merchant, resource);
    require(
        "APPROVED".equals(s(p, "status")) && Boolean.TRUE.equals(p.get("reserved")),
        409,
        "Payout approval is no longer valid");
    require(
        Instant.now().isBefore(time(p.get("approval_expires_at"))), 409, "Payout approval expired");
    member(db, id(p, "maker"), merchant, "OWNER", "FINANCE");
    member(db, id(p, "checker"), merchant, "OWNER");
    activeMerchant(db, merchant);
    var b =
        db.one(
            "select * from beneficiary where id=? and merchant_id=?",
            id(p, "beneficiary_id"),
            merchant);
    require(
        "ACTIVE".equals(s(b, "status")) && n(b, "version") == n(p, "beneficiary_version"),
        409,
        "Payout destination changed");
    noDispute(merchant);
    require(
        db.scalar(
                "select count(*) from operation where merchant_id=? and id<>? and status"
                    + " in('DISPATCHING','UNKNOWN') and kind"
                    + " in('refund','correction','open_dispute','resolve_dispute','flush')",
                Long.class,
                merchant,
                id(op, "id"))
            == 0,
        503,
        "Payout waiting for another uncertain monetary operation");
    var balances = ledger.balances(merchant);
    long dispatched =
        db.scalar(
            "select coalesce(sum(p.amount_minor),0) from payout p join operation o on"
                + " o.id=p.operation_id where p.merchant_id=? and p.reserved and p.id<>? and"
                + " o.status in('DISPATCHING','UNKNOWN')",
            Long.class,
            merchant,
            resource);
    long refunds =
        db.scalar(
            "select coalesce(sum(reserved_refund_minor),0) from payment where merchant_id=?",
            Long.class,
            merchant);
    long available =
        AvailableFunds.cents(
            balances.getOrDefault("merchant_payable", 0L),
            balances.getOrDefault("bank_control", 0L),
            dispatched,
            refunds);
    // Only earlier currently eligible proposals take priority. An unfundable prefix cannot starve a
    // smaller later payout.
    var earlier =
        db.query(
            "select p.amount_minor from payout p join operation o on o.id=p.operation_id join"
                + " beneficiary b on b.id=p.beneficiary_id join app_user maker on maker.id=p.maker"
                + " join app_user checker on checker.id=p.checker join membership mm on"
                + " mm.user_id=p.maker and mm.merchant_id=p.merchant_id join membership cm on"
                + " cm.user_id=p.checker and cm.merchant_id=p.merchant_id where p.merchant_id=? and"
                + " p.reserved and p.status='APPROVED' and o.status='QUEUED' and"
                + " p.approval_expires_at>now() and b.status='ACTIVE' and"
                + " b.version=p.beneficiary_version and maker.active and maker.verified and"
                + " maker.totp_confirmed and checker.active and checker.verified and"
                + " checker.totp_confirmed and mm.active and mm.role in('OWNER','FINANCE') and"
                + " cm.active and cm.role='OWNER' and (p.approved_at,p.id)<(?,?) order by"
                + " p.approved_at,p.id limit 10001",
            merchant,
            p.get("approved_at"),
            resource);
    require(earlier.size() <= 10000, 503, "Payout priority inspection bound reached");
    for (var queued : earlier) {
      long amount = n(queued, "amount_minor");
      if (amount <= available) available = Math.subtractExact(available, amount);
    }
    require(
        available >= n(p, "amount_minor"),
        503,
        "Payout waiting for available settled funds or an earlier eligible payout");
  }

  @SuppressWarnings("unchecked")
  public void apply(UUID merchant, String kind, UUID resource, Map<String, Object> result) {
    if (kind.equals("payout")) {
      var p =
          db.one(
              "select * from payout where id=? and merchant_id=? for update", resource, merchant);
      String state = s(result, "state");
      if (Set.of("SUCCEEDED", "COMMITTED").contains(state)) {
        require(s(result, "currency").equals("USD"), 409, "Payout currency mismatch");
        require(
            UUID.fromString(s(result, "resource_id")).equals(resource),
            409,
            "Payout identity mismatch");
        List<Map<String, Object>> sources =
            (List<Map<String, Object>>) result.getOrDefault("sources", List.of());
        var payoutSources =
            sources.stream().filter(x -> s(x, "event_type").equals("PAYOUT")).toList();
        require(
            payoutSources.size() == 1
                && n(payoutSources.getFirst(), "amount_minor") == -n(p, "amount_minor"),
            409,
            "Payout effect mismatch");
        var b = db.one("select token from beneficiary where id=?", id(p, "beneficiary_id"));
        Object dest = result.get("beneficiary");
        if (dest != null)
          require(dest.toString().equals(s(b, "token")), 409, "Payout destination mismatch");
        var source = payoutSources.getFirst();
        db.exec(
            "insert into"
                + " bank_transfer(id,merchant_id,payout_id,kind,reference,amount_minor,occurred_at)"
                + " values(?,?,?,'PAYOUT',?,?,?) on conflict(merchant_id,reference) do nothing",
            UUID.randomUUID(),
            merchant,
            resource,
            s(source, "source_id"),
            n(source, "amount_minor"),
            Timestamp.from(time(source.get("occurred_at"))));
        if (db.exec(
                "update payout set"
                    + " status='SUCCEEDED',reserved=false,version=version+1,updated_at=now() where"
                    + " id=? and status<>'SUCCEEDED'",
                resource)
            > 0)
          audit.record(
              merchant,
              null,
              "PAYOUT_COMPLETED",
              resource,
              "Authenticated provider payout applied");
      } else if (Set.of("FAILED", "NO_EFFECT", "CANCELLED").contains(state)) {
        if (db.exec(
                "update payout set"
                    + " status='FAILED',reserved=false,version=version+1,updated_at=now() where"
                    + " id=? and status not in('SUCCEEDED','FAILED','CANCELLED')",
                resource)
            > 0)
          audit.record(
              merchant, null, "PAYOUT_FAILED", resource, "Definitive no-effect or unsent failure");
      }
    }
    if (kind.equals("flush")) {
      for (var b : (List<Map<String, Object>>) result.getOrDefault("batches", List.of())) {
        validateBatch(merchant, b);
        UUID batch = UUID.randomUUID();
        int inserted =
            db.exec(
                "insert into"
                    + " settlement_batch(id,merchant_id,provider_id,net_minor,cutoff,transfer_reference,sources_json,occurred_at)"
                    + " values(?,?,?,?,?,?,?,?) on conflict(merchant_id,provider_id) do nothing",
                batch,
                merchant,
                s(b, "batch_id"),
                n(b, "net_minor"),
                s(b, "cutoff"),
                s(b, "transfer_reference"),
                CanonicalJson.string(b.getOrDefault("sources", List.of())),
                Timestamp.from(time(b.get("occurred_at"))));
        if (inserted > 0)
          audit.record(
              merchant,
              null,
              "SETTLEMENT_APPLIED",
              batch,
              "Authenticated batch details and exact transfer controls");
        if (n(b, "net_minor") != 0) {
          batch =
              id(
                  db.one(
                      "select id from settlement_batch where merchant_id=? and provider_id=?",
                      merchant,
                      s(b, "batch_id")),
                  "id");
          db.exec(
              "insert into"
                  + " bank_transfer(id,merchant_id,batch_id,kind,reference,amount_minor,occurred_at)"
                  + " values(?,?,?,'SETTLEMENT',?,?,?) on conflict(merchant_id,reference) do"
                  + " nothing",
              UUID.randomUUID(),
              merchant,
              batch,
              s(b, "transfer_reference"),
              n(b, "net_minor"),
              Timestamp.from(time(b.get("occurred_at"))));
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void validateBatch(UUID merchant, Map<String, Object> batch) {
    String identity = s(batch, "batch_id");
    List<Map<String, Object>> sources =
        (List<Map<String, Object>>) batch.getOrDefault("sources", List.of());
    List<Map<String, Object>> details =
        (List<Map<String, Object>>)
            batch.getOrDefault(
                "details",
                sources.stream()
                    .filter(
                        x ->
                            !Set.of("SETTLEMENT", "BATCH_ZERO", "PAYOUT")
                                .contains(s(x, "event_type")))
                    .toList());
    java.math.BigInteger net = java.math.BigInteger.ZERO;
    Set<String> seen = new HashSet<>();
    for (var source : details) {
      require(
          identity.equals(s(source, "batch_id")) && seen.add(s(source, "source_id")),
          409,
          "Settlement membership identity mismatch");
      require(
          !Set.of("SETTLEMENT", "BATCH_ZERO", "PAYOUT").contains(s(source, "event_type")),
          409,
          "Invalid settlement detail");
      net = net.add(java.math.BigInteger.valueOf(n(source, "amount_minor")));
    }
    require(
        net.equals(java.math.BigInteger.valueOf(n(batch, "net_minor"))),
        409,
        "Settlement detail total differs from frozen net");
    var transfers = sources.stream().filter(x -> s(x, "event_type").equals("SETTLEMENT")).toList();
    var zero = sources.stream().filter(x -> s(x, "event_type").equals("BATCH_ZERO")).toList();
    if (net.signum() == 0)
      require(
          transfers.isEmpty()
              && s(batch, "transfer_reference").isEmpty()
              && zero.size() == 1
              && identity.equals(s(zero.getFirst(), "source_id")),
          409,
          "Zero settlement must have exact zero evidence and no transfer");
    else
      require(
          transfers.size() == 1
              && n(transfers.getFirst(), "amount_minor") == n(batch, "net_minor")
              && s(transfers.getFirst(), "source_id").equals(s(batch, "transfer_reference"))
              && zero.isEmpty(),
          409,
          "Settlement needs one exact signed transfer");
    var old =
        db.maybe(
            "select * from settlement_batch where merchant_id=? and provider_id=?",
            merchant,
            identity);
    if (old.isPresent())
      require(
          n(old.get(), "net_minor") == n(batch, "net_minor")
              && s(old.get(), "sources_json")
                  .equals(com.hassankarkaba.mprp.protocol.CanonicalJson.string(sources))
              && s(old.get(), "transfer_reference").equals(s(batch, "transfer_reference")),
          409,
          "Immutable settlement batch changed");
  }

  @Transactional
  public UUID revisePayout(
      UUID merchant, UUID payout, UUID beneficiary, long expected, String key) {
    UUID actor = auth.actorForLocking();
    var initial = db.one("select maker from payout where id=? and merchant_id=?", payout, merchant);
    lockActors(db, actor, id(initial, "maker"));
    auth.require(merchant, "OWNER", "FINANCE");
    db.lockMerchant(merchant);
    var payload = map("version", expected, "beneficiary_id", beneficiary.toString());
    var replay = receipt(db, merchant, actor, "PAYOUT_REVISE", payout, key, payload);
    if (replay.isPresent()) return replay.get();
    var p =
        db.one("select * from payout where id=? and merchant_id=? for update", payout, merchant);
    version(p, expected);
    require(
        s(p, "status").equals("NEEDS_REAPPROVAL") && !Boolean.TRUE.equals(p.get("reserved")),
        409,
        "Only an unsent invalidated payout can be revised");
    activeMerchant(db, merchant);
    var b =
        db.one(
            "select * from beneficiary where id=? and merchant_id=? and status='ACTIVE'",
            beneficiary,
            merchant);
    String hash =
        CanonicalJson.sha256(
            map(
                "merchant_id",
                merchant.toString(),
                "beneficiary_id",
                beneficiary.toString(),
                "beneficiary_version",
                n(b, "version"),
                "amount_minor",
                n(p, "amount_minor"),
                "reason",
                s(p, "reason")));
    freezeProposal(payout);
    db.exec(
        "update payout set"
            + " maker=?,checker=null,beneficiary_id=?,beneficiary_version=?,payload_hash=?,status='PROPOSED',approved_at=null,approval_expires_at=null,operation_id=null,version=version+1,updated_at=now()"
            + " where id=?",
        actor,
        beneficiary,
        n(b, "version"),
        hash,
        payout);
    freezeProposal(payout);
    saveReceipt(db, merchant, actor, "PAYOUT_REVISE", payout, key, payload, payout);
    audit.record(
        merchant,
        actor,
        "PAYOUT_REVISED",
        payout,
        "Explicit new frozen destination; prior provider key remains cancelled");
    return payout;
  }

  private void freezeProposal(UUID payout) {
    var p = db.one("select * from payout where id=?", payout);
    var frozen =
        map(
            "payout_id",
            payout.toString(),
            "merchant_id",
            s(p, "merchant_id"),
            "maker",
            s(p, "maker"),
            "beneficiary_id",
            s(p, "beneficiary_id"),
            "beneficiary_version",
            n(p, "beneficiary_version"),
            "amount_minor",
            n(p, "amount_minor"),
            "reason",
            s(p, "reason"),
            "payload_hash",
            s(p, "payload_hash"),
            "version",
            n(p, "version"));
    db.exec(
        "insert into payout_proposal_version(payout_id,merchant_id,proposal_version,snapshot_json)"
            + " values(?,?,?,?) on conflict do nothing",
        payout,
        id(p, "merchant_id"),
        n(p, "version"),
        CanonicalJson.string(frozen));
  }
}
