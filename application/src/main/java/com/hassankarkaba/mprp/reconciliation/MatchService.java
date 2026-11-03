package com.hassankarkaba.mprp.reconciliation;

import static com.hassankarkaba.mprp.operations.DomainException.require;
import static com.hassankarkaba.mprp.settlements.WorkflowSupport.*;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchService {
  public record Allocation(UUID line, UUID source, long amount) {}

  private final Db db;
  private final Readiness readiness;
  private final Authz auth;
  private final com.hassankarkaba.mprp.settlements.WorkflowNotice audit;

  public MatchService(
      Db db,
      Authz auth,
      com.hassankarkaba.mprp.settlements.WorkflowNotice audit,
      Readiness readiness) {
    this.db = db;
    this.auth = auth;
    this.audit = audit;
    this.readiness = readiness;
  }

  public List<Map<String, Object>> remainingLines(UUID merchant) {
    return db.query(
        "select l.*,abs(l.amount_minor::numeric)-coalesce((select sum(a.amount_minor) from"
            + " allocation a join match_group g on g.id=a.group_id where a.line_id=l.id and"
            + " g.status='MATCHED'),0) remaining from statement_line l where merchant_id=? order by"
            + " occurred_at,id limit 500",
        merchant);
  }

  @Transactional
  public UUID match(UUID merchant, List<Allocation> allocations, String reason) {
    UUID actor = auth.platform("RECONCILIATION");
    db.lockMerchant(merchant);
    reconciliationReady(db, readiness);
    return matchLocked(merchant, actor, allocations, reason, false);
  }

  private UUID matchLocked(
      UUID merchant, UUID actor, List<Allocation> input, String reason, boolean acceptedWork) {
    require(!input.isEmpty() && input.size() <= 10000, 400, "Select 1 to 10000 allocations");
    Set<UUID> lines = new TreeSet<>(), sources = new TreeSet<>();
    Set<String> pairs = new HashSet<>();
    for (var a : input) {
      require(a.amount >= 0, 400, "Allocation must not be negative");
      require(pairs.add(a.line + ":" + a.source), 400, "Duplicate allocation pair");
      lines.add(a.line);
      sources.add(a.source);
    }
    require(
        lines.size() == 1 || sources.size() == 1,
        400,
        "Use one line with several sources, or several lines with one source");
    for (UUID line : lines)
      db.one(
          "select id from statement_line where id=? and merchant_id=? for update", line, merchant);
    for (UUID source : sources)
      db.one(
          "select id from economic_source where id=? and merchant_id=? for update",
          source,
          merchant);
    String layer = null;
    int sign = 0;
    Map<UUID, Long> allocated = new HashMap<>();
    for (var a : input) {
      var l = db.one("select * from statement_line where id=?", a.line);
      var s = db.one("select * from economic_source where id=?", a.source);
      if (layer == null) {
        layer = s(l, "kind");
        sign = Long.signum(n(l, "amount_minor"));
      }
      require(
          layer.equals(s(l, "kind")) && sign == Long.signum(n(l, "amount_minor")),
          409,
          "A group must use one layer and sign direction");
      require(compatible(l, s), 409, "Line and source identity or layer do not agree");
      open(merchant, time(l.get("occurred_at")));
      long remaining = remainingSource(a.source, layer, n(s, "amount_minor"));
      require(a.amount <= remaining, 409, "Source allocation exceeds remaining amount");
      allocated.merge(a.line, a.amount, Math::addExact);
    }
    for (UUID line : lines) {
      var l = db.one("select * from statement_line where id=?", line);
      long remaining = remainingLine(line, n(l, "amount_minor"));
      require(
          allocated.getOrDefault(line, 0L) == remaining,
          409,
          "Fully allocate each selected line's remaining amount");
    }
    UUID group = UUID.randomUUID();
    db.exec(
        "insert into match_group(id,merchant_id,actor,reason) values(?,?,?,?)",
        group,
        merchant,
        actor,
        reason == null ? "" : reason.trim());
    for (var a : input)
      db.exec(
          "insert into allocation(id,group_id,line_id,source_id,amount_minor) values(?,?,?,?,?)",
          UUID.randomUUID(),
          group,
          a.line,
          a.source,
          a.amount);
    if (acceptedWork)
      audit.recordAuditOnly(
          merchant,
          actor,
          "EVIDENCE_MATCHED",
          group,
          "Accepted exact matching allocation without a financial journal");
    else
      audit.record(
          merchant,
          actor,
          "EVIDENCE_MATCHED",
          group,
          "Evidence allocation without a financial journal");
    return group;
  }

  private long remainingLine(UUID line, long amount) {
    return Math.subtractExact(
        Math.abs(amount),
        db.scalar(
            "select coalesce(sum(a.amount_minor),0) from allocation a join match_group g on"
                + " g.id=a.group_id where a.line_id=? and g.status='MATCHED'",
            Long.class,
            line));
  }

  private long remainingSource(UUID source, String layer, long amount) {
    return Math.subtractExact(
        Math.abs(amount),
        db.scalar(
            "select coalesce(sum(a.amount_minor),0) from allocation a join match_group g on"
                + " g.id=a.group_id join statement_line l on l.id=a.line_id where a.source_id=? and"
                + " l.kind=? and g.status='MATCHED'",
            Long.class,
            source,
            layer));
  }

  private boolean compatible(Map<String, Object> l, Map<String, Object> source) {
    if (!Boolean.TRUE.equals(source.get("recognized"))
        || Long.signum(n(l, "amount_minor")) != Long.signum(n(source, "amount_minor")))
      return false;
    String kind = s(l, "kind"), event = s(source, "event_type");
    if (kind.equals("BANK")) {
      if (!Set.of("SETTLEMENT", "PAYOUT").contains(event)) return false;
      return s(l, "reference").equals(s(source, "provider_source_id"))
          || db.scalar(
                  "select count(*) from bank_transfer t left join settlement_batch b on"
                      + " b.id=t.batch_id where t.merchant_id=? and t.reference=? and"
                      + " t.amount_minor=? and ((?='PAYOUT' and t.payout_id=?) or (?='SETTLEMENT'"
                      + " and b.provider_id=?))",
                  Long.class,
                  id(l, "merchant_id"),
                  s(l, "reference"),
                  n(source, "amount_minor"),
                  event,
                  id(source, "resource_id"),
                  event,
                  s(source, "batch_id"))
              == 1;
    }
    if (kind.equals("PROCESSOR_TRANSFER")
        != Set.of("SETTLEMENT", "PAYOUT", "BATCH_ZERO").contains(event)) return false;
    if (!s(l, "event_type").equals(event)) return false;
    if (n(l, "amount_minor") == 0)
      return event.equals("BATCH_ZERO")
          && s(l, "source_id").equals(s(source, "provider_source_id"))
          && s(l, "batch_id").equals(s(source, "batch_id"))
          && s(l, "source_id").equals(s(l, "batch_id"));
    return s(l, "source_id").equals(s(source, "provider_source_id"))
            && s(l, "reference").equals(s(source, "provider_source_id"))
        || s(l, "source_id").equals(s(source, "batch_id"))
            && s(l, "reference").equals(s(source, "batch_id"));
  }

  private void open(UUID merchant, Instant occurred) {
    LocalDate month = occurred.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    var p =
        db.maybe(
            "select status from accounting_period where merchant_id=? and month=?",
            merchant,
            month);
    require(
        p.isEmpty() || !Set.of("CLOSED", "REOPEN_PROPOSED").contains(s(p.get(), "status")),
        409,
        "Reopen the accounting month before changing evidence");
  }

  public record BatchResult(int matched, int examined, Map<String, Object> last) {}

  @Transactional
  public BatchResult autoMatchBatch(
      UUID merchant,
      UUID actor,
      List<Map<String, Object>> lines,
      java.util.function.BooleanSupplier guard) {
    lockActors(db, actor);
    platform(db, actor, "RECONCILIATION");
    db.lockMerchant(merchant);
    require(guard.getAsBoolean(), 409, "Matching worker lease is no longer current");
    int groups = 0, examined = 0;
    Map<String, Object> last = null;
    for (var line : lines) {
      if (groups >= 50) break;
      last = line;
      examined++;
      long remaining = remainingLine(id(line, "id"), n(line, "amount_minor"));
      if (remaining == 0
          && (n(line, "amount_minor") != 0
              || db.scalar(
                      "select count(*) from allocation a join match_group g on g.id=a.group_id"
                          + " where a.line_id=? and g.status='MATCHED'",
                      Long.class,
                      id(line, "id"))
                  > 0)) continue;
      var candidates =
          db.query(
              "select * from economic_source where merchant_id=? and recognized and"
                  + " ((provider_source_id=?) or (batch_id=?)) order by occurred_at,id limit 501",
              merchant,
              s(line, "source_id"),
              s(line, "source_id"));
      var exact =
          candidates.stream()
              .filter(
                  source ->
                      compatible(line, source)
                          && remainingSource(
                                  id(source, "id"), s(line, "kind"), n(source, "amount_minor"))
                              == remaining)
              .toList();
      String reason = exact.size() > 1 ? "Ambiguous exact candidates" : "No unique exact source";
      if (exact.size() == 1) {
        try {
          matchLocked(
              merchant,
              actor,
              List.of(new Allocation(id(line, "id"), id(exact.getFirst(), "id"), remaining)),
              "Unique exact automatic match",
              true);
          groups++;
          continue;
        } catch (DomainException blocked) {
          reason = blocked.getMessage();
        }
      }
      db.exec(
          "insert into"
              + " reconciliation_exception(id,merchant_id,category,source_reference,line_id,amount_minor,reason)"
              + " values(?,?,'UNMATCHED_LINE',?,?,?,?) on"
              + " conflict(merchant_id,category,source_reference) do nothing",
          UUID.randomUUID(),
          merchant,
          id(line, "id").toString(),
          id(line, "id"),
          n(line, "amount_minor"),
          reason);
    }
    require(guard.getAsBoolean(), 409, "Matching worker lease expired before commitment");
    return new BatchResult(groups, examined, last);
  }

  @Transactional
  public void reverse(UUID group, long expected, String reason) {
    UUID actor = auth.platform("RECONCILIATION");
    reason = text(reason, 500);
    var initial = db.one("select * from match_group where id=?", group);
    UUID merchant = id(initial, "merchant_id");
    db.lockMerchant(merchant);
    reconciliationReady(db, readiness);
    var g = db.one("select * from match_group where id=? for update", group);
    version(g, expected);
    require(s(g, "status").equals("MATCHED"), 409, "Match is already reversed");
    for (var l :
        db.query(
            "select distinct l.occurred_at from allocation a join statement_line l on"
                + " l.id=a.line_id where a.group_id=?",
            group)) open(merchant, time(l.get("occurred_at")));
    db.exec(
        "update match_group set status='REVERSED',reversed_at=now(),reason=?,version=version+1"
            + " where id=?",
        reason,
        group);
    audit.record(merchant, actor, "EVIDENCE_REVERSED", group, reason);
  }

  @Transactional
  public void exceptionAction(UUID exception, long expected, String action, String reason) {
    UUID actor = auth.platform("RECONCILIATION");
    reason = text(reason, 2000);
    var initial = db.one("select merchant_id from reconciliation_exception where id=?", exception);
    db.lockMerchant(id(initial, "merchant_id"));
    var e = db.one("select * from reconciliation_exception where id=? for update", exception);
    version(e, expected);
    String state =
        switch (action) {
          case "investigate" -> "INVESTIGATING";
          case "wait" -> "WAITING_PROVIDER";
          case "resolve" -> "RESOLVED";
          case "accept" -> "ACCEPTED_NONFINANCIAL";
          default -> throw new DomainException(400, "Unsupported exception action");
        };
    if (state.equals("ACCEPTED_NONFINANCIAL"))
      require(
          n(e, "amount_minor") == 0
              && Set.of("DUPLICATE_NONFINANCIAL", "IRRELEVANT_NONFINANCIAL")
                  .contains(s(e, "category")),
          409,
          "Monetary differences cannot be waived");
    if (state.equals("RESOLVED")) {
      reconciliationReady(db, readiness);
      boolean complete;
      if (e.get("line_id") != null) {
        UUID lineId = id(e, "line_id");
        var line = db.one("select * from statement_line where id=?", lineId);
        var sourceRows =
            db.query(
                "select distinct s.id from allocation a join match_group g on g.id=a.group_id join"
                    + " economic_source s on s.id=a.source_id where a.line_id=? and"
                    + " g.status='MATCHED'",
                lineId);
        complete =
            !sourceRows.isEmpty()
                && remainingLine(lineId, n(line, "amount_minor")) == 0
                && sourceRows.stream().allMatch(source -> evidenceComplete(id(source, "id")));
      } else {
        UUID source;
        try {
          source = UUID.fromString(s(e, "source_reference"));
        } catch (IllegalArgumentException ex) {
          throw new DomainException(409, "Exception requires authenticated source evidence");
        }
        complete = evidenceComplete(source);
      }
      require(complete, 409, "Source journal and all required active evidence are incomplete");
    }

    db.exec(
        "insert into exception_history(id,exception_id,merchant_id,actor,state,reason)"
            + " values(?,?,?,?,?,?)",
        UUID.randomUUID(),
        exception,
        id(e, "merchant_id"),
        actor,
        state,
        reason);
    db.exec(
        "update reconciliation_exception set"
            + " state=?,owner=?,reason=?,version=version+1,resolved_at=case when ?"
            + " in('RESOLVED','ACCEPTED_NONFINANCIAL') then clock_timestamp() else null end where"
            + " id=?",
        state,
        actor,
        reason,
        state,
        exception);
    audit.record(id(e, "merchant_id"), actor, "EXCEPTION_" + state, exception, reason);
  }

  public boolean evidenceComplete(UUID source) {
    var s = db.one("select * from economic_source where id=?", source);
    if (!Boolean.TRUE.equals(s.get("recognized"))) return false;
    if (!s(s, "event_type").equals("BATCH_ZERO")
        && db.scalar("select count(*) from journal where source_id=?", Long.class, source) != 1)
      return false;
    List<String> layers =
        Set.of("SETTLEMENT", "PAYOUT", "BATCH_ZERO").contains(s(s, "event_type"))
            ? (s(s, "event_type").equals("BATCH_ZERO")
                ? List.of("PROCESSOR_TRANSFER")
                : List.of("PROCESSOR_TRANSFER", "BANK"))
            : List.of("PROCESSOR_ECONOMIC");
    for (String layer : layers) {
      if (remainingSource(source, layer, n(s, "amount_minor")) != 0) return false;
      if (n(s, "amount_minor") == 0
          && db.scalar(
                  "select count(*) from allocation a join match_group g on g.id=a.group_id join"
                      + " statement_line l on l.id=a.line_id where a.source_id=? and l.kind=? and"
                      + " g.status='MATCHED'",
                  Long.class,
                  source,
                  layer)
              != 1) return false;
    }
    return true;
  }
}
