package com.hassankarkaba.mprp.reconciliation;

import static com.hassankarkaba.mprp.operations.DomainException.require;
import static com.hassankarkaba.mprp.settlements.WorkflowSupport.*;

import com.hassankarkaba.mprp.accounting.LedgerService;
import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.*;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PeriodService {
  private final Db db;
  private final Readiness readiness;
  private final Authz auth;
  private final LedgerService ledger;
  private final MatchService matches;
  private final ProviderClient provider;
  private final TransactionTemplate tx;
  private final com.hassankarkaba.mprp.settlements.WorkflowNotice audit;

  public PeriodService(
      Db db,
      Authz auth,
      LedgerService ledger,
      MatchService matches,
      ProviderClient provider,
      PlatformTransactionManager manager,
      com.hassankarkaba.mprp.settlements.WorkflowNotice audit,
      Readiness readiness) {
    this.db = db;
    this.auth = auth;
    this.ledger = ledger;
    this.matches = matches;
    this.provider = provider;
    this.tx = new TransactionTemplate(manager);
    this.audit = audit;
    this.readiness = readiness;
  }

  public void create(UUID merchant, LocalDate month) {
    UUID actor = auth.platform("RECONCILIATION");
    require(
        month.getDayOfMonth() == 1
            && !month.isAfter(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)),
        400,
        "Use a current or past UTC first-of-month date");
    tx.executeWithoutResult(
        status -> {
          db.lockMerchant(merchant);
          db.exec(
              "insert into accounting_period(merchant_id,month,status) values(?,?,'OPEN') on"
                  + " conflict do nothing",
              merchant,
              month);
          audit.record(merchant, actor, "PERIOD_CREATED", merchant, month.toString());
        });
  }

  public UUID propose(UUID merchant, LocalDate month, String kind, String reason) {
    UUID actor = auth.platform("RECONCILIATION");
    require(Set.of("CLOSE", "REOPEN").contains(kind), 400, "Unsupported period action");
    if (kind.equals("REOPEN")) reason = text(reason, 2000);
    else reason = "";
    final String why = reason;
    List<Map<String, Object>> evidence =
        kind.equals("CLOSE") ? fetchPartitions(merchant, month) : List.of();
    return tx.execute(
        status -> {
          auth.platform("RECONCILIATION");
          db.lockMerchant(merchant);
          reconciliationReady(db, readiness);
          var p =
              db.one(
                  "select * from accounting_period where merchant_id=? and month=? for update",
                  merchant,
                  month);
          require(
              s(p, "status").equals(kind.equals("CLOSE") ? "OPEN" : "CLOSED"),
              409,
              "Period is not eligible for this proposal");
          Map<String, Object> snapshot =
              kind.equals("CLOSE")
                  ? snapshot(merchant, month, evidence)
                  : reopenSnapshot(merchant, month);
          UUID id = UUID.randomUUID();
          db.exec(
              "insert into"
                  + " period_proposal(id,merchant_id,month,maker,decision_kind,reason,snapshot_json,snapshot_hash,prior_report_id)"
                  + " values(?,?,?,?,?,?,?,?,?)",
              id,
              merchant,
              month,
              actor,
              kind,
              why,
              CanonicalJson.string(snapshot),
              CanonicalJson.sha256(snapshot),
              snapshot.get("prior_report_id") == null
                  ? null
                  : UUID.fromString(snapshot.get("prior_report_id").toString()));
          db.exec(
              "update accounting_period set status=?,version=version+1 where merchant_id=? and"
                  + " month=?",
              kind.equals("CLOSE") ? "CLOSE_PROPOSED" : "REOPEN_PROPOSED",
              merchant,
              month);
          audit.record(merchant, actor, "PERIOD_" + kind + "_PROPOSED", id, why);
          return id;
        });
  }

  public UUID decide(UUID proposal, long expected, String decision, String key) {
    UUID actor = auth.platform("FINANCE_APPROVER");
    require(Set.of("approve", "reject").contains(decision), 400, "Unsupported decision");
    var initial = db.one("select * from period_proposal where id=?", proposal);
    UUID merchant = id(initial, "merchant_id");
    var request =
        map(
            "version",
            expected,
            "decision",
            decision,
            "proposal_hash",
            s(initial, "snapshot_hash"));
    var old = receipt(db, merchant, actor, "PERIOD_DECISION", proposal, key, request);
    if (old.isPresent()) return old.get();
    auth.stepUp();
    LocalDate month = ((java.sql.Date) initial.get("month")).toLocalDate();
    List<Map<String, Object>> evidence =
        s(initial, "decision_kind").equals("CLOSE") && decision.equals("approve")
            ? fetchPartitions(merchant, month)
            : List.of();
    return tx.execute(
        status -> {
          lockActors(db, actor, id(initial, "maker"));
          auth.platform("FINANCE_APPROVER");
          auth.stepUp();
          db.lockMerchant(merchant);
          var replay = receipt(db, merchant, actor, "PERIOD_DECISION", proposal, key, request);
          if (replay.isPresent()) return replay.get();
          reconciliationReady(db, readiness);
          var p = db.one("select * from period_proposal where id=? for update", proposal);
          version(p, expected);
          require(s(p, "state").equals("PROPOSED"), 409, "Proposal is already decided");
          require(!actor.equals(id(p, "maker")), 403, "A different finance approver must decide");
          platform(db, id(p, "maker"), "RECONCILIATION");
          platform(db, actor, "FINANCE_APPROVER");
          boolean close = s(p, "decision_kind").equals("CLOSE");
          if (decision.equals("approve")) {
            var current =
                close ? snapshot(merchant, month, evidence) : reopenSnapshot(merchant, month);
            require(
                CanonicalJson.sha256(current).equals(s(p, "snapshot_hash")),
                409,
                "Period evidence changed. Reject this proposal and create another.");
          }
          UUID result = proposal;
          if (decision.equals("approve")) {
            if (close) {
              long version =
                  db.scalar(
                      "select coalesce(max(version),0)+1 from period_report where merchant_id=? and"
                          + " month=?",
                      Long.class,
                      merchant,
                      month);
              result = UUID.randomUUID();
              db.exec(
                  "insert into"
                      + " period_report(id,merchant_id,month,version,snapshot_json,snapshot_hash,proposal_id)"
                      + " values(?,?,?,?,?,?,?)",
                  result,
                  merchant,
                  month,
                  version,
                  s(p, "snapshot_json"),
                  s(p, "snapshot_hash"),
                  proposal);
              db.exec(
                  "update accounting_period set status='CLOSED',version=version+1 where"
                      + " merchant_id=? and month=?",
                  merchant,
                  month);
            } else {
              db.exec(
                  "update period_report set valid=false where merchant_id=? and month=? and valid",
                  merchant,
                  month);
              db.exec(
                  "update accounting_period set status='OPEN',version=version+1 where merchant_id=?"
                      + " and month=?",
                  merchant,
                  month);
            }
          } else
            db.exec(
                "update accounting_period set status=?,version=version+1 where merchant_id=? and"
                    + " month=?",
                close ? "OPEN" : "CLOSED",
                merchant,
                month);
          db.exec(
              "update period_proposal set checker=?,state=?,decided_at=now(),version=version+1"
                  + " where id=?",
              actor,
              decision.equals("approve") ? "APPROVED" : "REJECTED",
              proposal);
          saveReceipt(db, merchant, actor, "PERIOD_DECISION", proposal, key, request, result);
          audit.record(
              merchant,
              actor,
              "PERIOD_" + decision.toUpperCase(Locale.ROOT),
              proposal,
              s(p, "reason"));
          return result;
        });
  }

  private Map<String, Object> reopenSnapshot(UUID merchant, LocalDate month) {
    var report =
        db.one(
            "select * from period_report where merchant_id=? and month=? and valid order by version"
                + " desc limit 1",
            merchant,
            month);
    return map(
        "merchant_id",
        merchant.toString(),
        "month",
        month.toString(),
        "prior_report_id",
        id(report, "id").toString(),
        "prior_version",
        n(report, "version"),
        "prior_hash",
        s(report, "snapshot_hash"));
  }

  private Map<String, Object> snapshot(
      UUID merchant, LocalDate month, List<Map<String, Object>> evidence) {
    Instant start = month.atStartOfDay(ZoneOffset.UTC).toInstant(),
        end = month.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    require(!Instant.now().isBefore(end), 409, "UTC month has not ended");
    var trial = trialBalanceThrough(merchant, month);
    require(
        trial.get("adjustment_suspense").signum() == 0, 409, "Adjustment suspense must be zero");
    require(
        db.scalar(
                "select count(*) from operation where merchant_id=? and status"
                    + " in('QUEUED','DISPATCHING','UNKNOWN')",
                Long.class,
                merchant)
            == 0,
        409,
        "Unfinished monetary operations block close");
    var sources =
        db.query(
            "select * from economic_source where merchant_id=? and occurred_at<? order by"
                + " occurred_at,id",
            merchant,
            java.sql.Timestamp.from(end));
    for (var source : sources)
      require(
          matches.evidenceComplete(id(source, "id")),
          409,
          "Every recognized source needs its journal and all required evidence");
    require(
        db.scalar(
                "select count(*) from reconciliation_exception e left join statement_line l on"
                    + " l.id=e.line_id where e.merchant_id=? and e.state not"
                    + " in('RESOLVED','ACCEPTED_NONFINANCIAL') and (l.occurred_at<? or"
                    + " exists(select 1 from economic_source x where x.id::text=e.source_reference"
                    + " and x.merchant_id=e.merchant_id and (x.occurred_at<? or"
                    + " x.posting_month<=?)) or (e.line_id is null and not exists(select 1 from"
                    + " economic_source x where x.id::text=e.source_reference and"
                    + " x.merchant_id=e.merchant_id) and e.created_at<?))",
                Long.class,
                merchant,
                java.sql.Timestamp.from(end),
                java.sql.Timestamp.from(end),
                month,
                java.sql.Timestamp.from(end))
            == 0,
        409,
        "Unresolved reconciliation exceptions block close");
    for (var line :
        db.query(
            "select l.id,l.amount_minor,coalesce(sum(a.amount_minor) filter(where"
                + " g.status='MATCHED'),0) allocated from statement_line l left join allocation a"
                + " on a.line_id=l.id left join match_group g on g.id=a.group_id where"
                + " l.merchant_id=? and l.occurred_at<? group by l.id",
            merchant,
            java.sql.Timestamp.from(end)))
      require(
          Math.abs(n(line, "amount_minor")) == n(line, "allocated"),
          409,
          "Statement lines remain unallocated");
    List<Map<String, Object>> partitions = new ArrayList<>();
    for (var part : evidence) {
      String manifest = s(part, "manifest_id");
      var i =
          db.maybe(
                  "select * from statement_import where merchant_id=? and manifest_id=? and"
                      + " status='CONFIRMED'",
                  merchant,
                  manifest)
              .orElseThrow(
                  () ->
                      new DomainException(
                          409,
                          "Confirm required "
                              + s(part, "kind")
                              + " manifest "
                              + manifest
                              + " for "
                              + s(part, "period_start")
                              + " through "
                              + s(part, "period_end")
                              + " before closing"));
      require(
          s(i, "manifest_json").equals(CanonicalJson.string(part)),
          409,
          "Manifest evidence changed or is incomplete");
      partitions.add(
          map(
              "manifest_id",
              manifest,
              "kind",
              s(part, "kind"),
              "period_start",
              s(part, "period_start"),
              "period_end",
              s(part, "period_end"),
              "source_high_water",
              part.getOrDefault("source_high_water", part.getOrDefault("high_water", 0)),
              "import_id",
              id(i, "id").toString(),
              "file_hash",
              s(i, "file_hash")));
    }
    List<String> journals =
        db
            .query(
                "select id from journal where merchant_id=? and period_month<=? order by sequence",
                merchant,
                month)
            .stream()
            .map(r -> s(r, "id"))
            .toList();
    List<String> allocations =
        db
            .query(
                "select a.id from allocation a join match_group g on g.id=a.group_id join"
                    + " statement_line l on l.id=a.line_id where g.merchant_id=? and"
                    + " g.status='MATCHED' and l.occurred_at<? order by a.id",
                merchant,
                java.sql.Timestamp.from(end))
            .stream()
            .map(r -> s(r, "id"))
            .toList();
    var m = db.one("select ledger_sequence from merchant where id=?", merchant);
    return map(
        "merchant_id",
        merchant.toString(),
        "month",
        month.toString(),
        "cutoff",
        end,
        "ledger_sequence",
        n(m, "ledger_sequence"),
        "journal_ids",
        chunks(journals),
        "source_ids",
        chunks(sources.stream().map(r -> s(r, "id")).toList()),
        "allocation_ids",
        chunks(allocations),
        "manifest_partitions",
        partitions,
        "trial_balance",
        trial,
        "timing_differences",
        batchControls(merchant, end),
        "occurrence_source_totals",
        sourceTotals(merchant, start, end));
  }

  private static List<List<String>> chunks(List<String> values) {
    List<List<String>> result = new ArrayList<>();
    for (int n = 0; n < values.size(); n += 5000)
      result.add(values.subList(n, Math.min(values.size(), n + 5000)));
    return result;
  }

  private List<Map<String, Object>> fetchPartitions(UUID merchant, LocalDate month) {
    Instant start = month.atStartOfDay(ZoneOffset.UTC).toInstant(),
        end = month.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    List<Map<String, Object>> parts = new ArrayList<>();
    for (String kind : List.of("PROCESSOR_ECONOMIC", "PROCESSOR_TRANSFER", "BANK"))
      split(merchant, kind, start, end, parts, 0);
    return List.copyOf(parts);
  }

  private void split(
      UUID merchant,
      String kind,
      Instant start,
      Instant end,
      List<Map<String, Object>> output,
      int depth) {
    require(depth < 32 && output.size() < 10000, 409, "Manifest partition bound exceeded");
    String path =
        "/v1/statements/?provider_account_id="
            + merchant
            + "&kind="
            + kind
            + "&period_start="
            + URLEncoder.encode(CanonicalJson.instant(start), StandardCharsets.UTF_8)
            + "&period_end="
            + URLEncoder.encode(CanonicalJson.instant(end), StandardCharsets.UTF_8);
    var m = provider.get(path);
    if (Boolean.TRUE.equals(m.get("requires_split"))) {
      require(
          Duration.between(start, end).compareTo(Duration.ofSeconds(1)) > 0,
          409,
          "A one-second interval exceeds 10000 statement rows");
      Instant middle = start.plusMillis(Duration.between(start, end).toMillis() / 2);
      split(merchant, kind, start, middle, output, depth + 1);
      split(merchant, kind, middle, end, output, depth + 1);
    } else output.add(m);
  }

  public UUID cancelProposal(UUID proposal, long expected, String key, String reason) {
    UUID actor = auth.platform("RECONCILIATION");
    reason = text(reason, 2000);
    var initial = db.one("select * from period_proposal where id=?", proposal);
    UUID merchant = id(initial, "merchant_id");
    var payload =
        map(
            "version",
            expected,
            "decision",
            "cancel",
            "proposal_hash",
            s(initial, "snapshot_hash"),
            "reason",
            reason);
    final String why = reason;
    return tx.execute(
        status -> {
          lockActors(db, actor, id(initial, "maker"));
          auth.platform("RECONCILIATION");
          db.lockMerchant(merchant);
          var replay = receipt(db, merchant, actor, "PERIOD_CANCEL", proposal, key, payload);
          if (replay.isPresent()) return replay.get();
          auth.stepUp();
          var p = db.one("select * from period_proposal where id=? for update", proposal);
          version(p, expected);
          require(s(p, "state").equals("PROPOSED"), 409, "Period proposal is already terminal");
          long makerCurrent =
              db.scalar(
                  "select count(*) from app_user where id=? and active and verified and"
                      + " totp_confirmed and platform_role='RECONCILIATION'",
                  Long.class,
                  id(p, "maker"));
          require(
              actor.equals(id(p, "maker")) || makerCurrent == 0,
              403,
              "Only the current maker may withdraw a valid proposal");
          db.exec(
              "update period_proposal set state='CANCELLED',decided_at=now(),version=version+1"
                  + " where id=?",
              proposal);
          db.exec(
              "update accounting_period set status=?,version=version+1 where merchant_id=? and"
                  + " month=?",
              s(p, "decision_kind").equals("CLOSE") ? "OPEN" : "CLOSED",
              merchant,
              p.get("month"));
          saveReceipt(db, merchant, actor, "PERIOD_CANCEL", proposal, key, payload, proposal);
          audit.recordAccepted(merchant, actor, "PERIOD_PROPOSAL_CANCELLED", proposal, why);
          return proposal;
        });
  }

  private Map<String, java.math.BigInteger> trialBalanceThrough(UUID merchant, LocalDate month) {
    var result = new LinkedHashMap<String, java.math.BigInteger>();
    for (String account :
        List.of("processor_clearing", "merchant_payable", "bank_control", "adjustment_suspense"))
      result.put(account, java.math.BigInteger.ZERO);
    for (var row :
        db.query(
            "select e.account,sum(e.debit::numeric)-sum(e.credit::numeric) balance from"
                + " ledger_entry e join journal j on j.id=e.journal_id where j.merchant_id=? and"
                + " j.period_month<=? group by e.account",
            merchant,
            month)) {
      var amount = new java.math.BigDecimal(row.get("balance").toString()).toBigIntegerExact();
      result.put(
          s(row, "account"),
          s(row, "account").equals("merchant_payable") ? amount.negate() : amount);
    }
    return result;
  }

  private Map<String, java.math.BigInteger> sourceTotals(
      UUID merchant, Instant start, Instant end) {
    var totals = new TreeMap<String, java.math.BigInteger>();
    for (var row :
        db.query(
            "select event_type,sum(amount_minor::numeric) total from economic_source where"
                + " merchant_id=? and occurred_at>=? and occurred_at<? group by event_type",
            merchant,
            java.sql.Timestamp.from(start),
            java.sql.Timestamp.from(end)))
      totals.put(
          s(row, "event_type"),
          new java.math.BigDecimal(row.get("total").toString()).toBigIntegerExact());
    return totals;
  }

  private List<Map<String, Object>> batchControls(UUID merchant, Instant cutoff) {
    List<Map<String, Object>> timing = new ArrayList<>();
    for (var ref :
        db.query(
            "select distinct batch_id from economic_source where merchant_id=? and occurred_at<?"
                + " and batch_id is not null",
            merchant,
            java.sql.Timestamp.from(cutoff))) {
      String batch = s(ref, "batch_id");
      var record =
          db.maybe(
              "select * from settlement_batch where merchant_id=? and provider_id=?",
              merchant,
              batch);
      require(
          record.isPresent(),
          409,
          "Flush and authenticate settlement batch before closing: " + batch);
      var b = record.get();
      var total =
          db.scalar(
                  "select coalesce(sum(amount_minor::numeric),0) from economic_source where"
                      + " merchant_id=? and batch_id=? and event_type not"
                      + " in('SETTLEMENT','BATCH_ZERO')",
                  java.math.BigDecimal.class,
                  merchant,
                  batch)
              .toBigIntegerExact();
      require(
          total.equals(java.math.BigInteger.valueOf(n(b, "net_minor"))),
          409,
          "Settlement membership does not equal frozen batch net");
      var transfers =
          db.query(
              "select * from bank_transfer where merchant_id=? and batch_id=?",
              merchant,
              id(b, "id"));
      if (total.signum() == 0) {
        require(transfers.isEmpty(), 409, "Zero batch must not have a bank transfer");
        require(
            db.scalar(
                    "select count(*) from economic_source where merchant_id=? and"
                        + " event_type='BATCH_ZERO' and provider_source_id=? and batch_id=? and"
                        + " amount_minor=0",
                    Long.class,
                    merchant,
                    batch,
                    batch)
                == 1,
            409,
            "Exact zero-batch identity is missing");
      } else {
        require(
            transfers.size() == 1 && n(transfers.getFirst(), "amount_minor") == n(b, "net_minor"),
            409,
            "Settlement needs one exact transfer");
        var transfer = transfers.getFirst();
        if (!time(transfer.get("occurred_at")).isBefore(cutoff)) {
          var cutoffNet =
              db.scalar(
                      "select coalesce(sum(amount_minor::numeric),0) from economic_source where"
                          + " merchant_id=? and batch_id=? and occurred_at<? and event_type not"
                          + " in('SETTLEMENT','BATCH_ZERO')",
                      java.math.BigDecimal.class,
                      merchant,
                      batch,
                      java.sql.Timestamp.from(cutoff))
                  .toBigIntegerExact();
          timing.add(
              map(
                  "batch_id",
                  batch,
                  "cutoff_economic_net_minor",
                  cutoffNet,
                  "full_batch_net_minor",
                  n(b, "net_minor"),
                  "transfer_reference",
                  s(transfer, "reference"),
                  "transferred_at",
                  time(transfer.get("occurred_at"))));
        }
      }
    }
    return timing;
  }
}
