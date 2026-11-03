package com.hassankarkaba.mprp.accounting;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.operations.*;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
  private final Db db;
  private final AuditService audit;
  private final LedgerSafety safety;

  public LedgerService(Db db, AuditService audit, LedgerSafety safety) {
    this.db = db;
    this.audit = audit;
    this.safety = safety;
  }

  @Transactional
  public void apply(UUID merchant, UUID operation, List<Map<String, Object>> sources) {
    db.lockMerchant(merchant);
    safety.requirePosting();
    for (var source : sources) applyOne(merchant, operation, source);
  }

  private void applyOne(UUID merchant, UUID operation, Map<String, Object> s) {
    String provider = s.get("source_id").toString(), type = s.get("event_type").toString();
    long amount = ((Number) s.get("amount_minor")).longValue();
    UUID resource = UUID.fromString(s.get("resource_id").toString());
    Instant occurred = Instant.parse(s.get("occurred_at").toString());
    String batch = s.get("batch_id") == null ? null : s.get("batch_id").toString();
    require(occurred.isBefore(Instant.now().plusSeconds(300)), 409, "Future provider source");
    var existing =
        db.maybe(
            "select * from economic_source where merchant_id=? and provider_source_id=? and"
                + " event_type=?",
            merchant,
            provider,
            type);
    if (existing.isPresent()) {
      var x = existing.get();
      require(
          Db.number(x, "amount_minor") == amount
              && x.get("resource_id").equals(resource)
              && ((Timestamp) x.get("occurred_at")).toInstant().equals(occurred),
          409,
          "Conflicting source identity");
      if (x.get("batch_id") == null && batch != null)
        db.exec("update economic_source set batch_id=? where id=?", batch, x.get("id"));
      else
        require(
            Objects.equals(x.get("batch_id"), batch) || batch == null,
            409,
            "Conflicting source batch");
      return;
    }
    if (type.equals("CAPTURE") || type.equals("FEE")) {
      var p = db.one("select * from payment where id=? and merchant_id=?", resource, merchant);
      require(
          db.scalar(
                  "select count(*) from operation where merchant_id=? and resource_id=? and kind"
                      + " in('authorize','capture')",
                  Long.class,
                  merchant,
                  resource)
              > 0,
          409,
          "Capture lacks admitted operation");
      long captured =
          type.equals("CAPTURE") ? amount : sourcesCapturedHint(resource, Math.abs(amount));
      require(amount != 0, 409, "Zero economic source");
      if (type.equals("FEE") && captured > 0)
        require(
            Math.abs(amount) == com.hassankarkaba.mprp.protocol.Money.fee(captured),
            409,
            "Fee policy mismatch");
    }
    LocalDate occurrence = occurred.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1),
        posting = occurrence;
    for (int n = 0; n < 2400; n++) {
      db.exec(
          "insert into accounting_period(merchant_id,month,status) values(?,?,'OPEN') on conflict"
              + " do nothing",
          merchant,
          posting);
      String status =
          db.scalar(
              "select status from accounting_period where merchant_id=? and month=? for update",
              String.class,
              merchant,
              posting);
      if (!Set.of("CLOSED", "REOPEN_PROPOSED").contains(status)) break;
      posting = posting.plusMonths(1);
      require(n < 2399, 409, "No open posting period");
    }
    UUID id = UUID.randomUUID();
    boolean recognized = !type.startsWith("CORRECTION_");
    db.exec(
        "insert into"
            + " economic_source(id,merchant_id,provider_source_id,event_type,amount_minor,occurred_at,batch_id,resource_id,recognized,posting_month)"
            + " values(?,?,?,?,?,?,?,?,?,?)",
        id,
        merchant,
        provider,
        type,
        amount,
        Timestamp.from(occurred),
        batch,
        resource,
        recognized,
        posting);
    if (!recognized || !posting.equals(occurrence)) {
      db.exec(
          "insert into"
              + " reconciliation_exception(id,merchant_id,source_reference,category,amount_minor,state,reason)"
              + " values(?,?,?,?,?,'OPEN',?)",
          UUID.randomUUID(),
          merchant,
          id.toString(),
          !recognized ? "UNRECOGNIZED_CORRECTION" : "LATE_EVENT",
          amount,
          !recognized
              ? "Authenticated correction requires independent recognition"
              : "Occurrence period closed; posted to next open period");
    }
    if (recognized && amount != 0) post(merchant, id, type, amount, posting);
  }

  private long sourcesCapturedHint(UUID payment, long fee) {
    Long value =
        db.scalar(
            "select coalesce(max(amount_minor),0) from economic_source where resource_id=? and"
                + " event_type='CAPTURE'",
            Long.class,
            payment);
    return value;
  }

  @Transactional
  public void recognize(UUID merchant, UUID source) {
    db.lockMerchant(merchant);
    safety.requirePosting();
    var s =
        db.one(
            "select * from economic_source where id=? and merchant_id=? for update",
            source,
            merchant);
    require(s.get("event_type").toString().startsWith("CORRECTION_"), 400, "Not a correction");
    db.exec("update economic_source set recognized=true where id=?", source);
    if (db.scalar("select count(*) from journal where source_id=?", Long.class, source) == 0)
      post(
          merchant,
          source,
          s.get("event_type").toString(),
          Db.number(s, "amount_minor"),
          ((java.sql.Date) s.get("posting_month")).toLocalDate());
  }

  private void post(UUID merchant, UUID source, String type, long signed, LocalDate month) {
    String debit, credit;
    switch (type) {
      case "CAPTURE", "DISPUTE_WON", "CORRECTION_CREDIT" -> {
        debit = "processor_clearing";
        credit = "merchant_payable";
      }
      case "FEE", "REFUND", "DISPUTE_OPEN", "CORRECTION_DEBIT" -> {
        debit = "merchant_payable";
        credit = "processor_clearing";
      }
      case "PAYOUT" -> {
        debit = "merchant_payable";
        credit = "bank_control";
      }
      case "SETTLEMENT" -> {
        debit = signed > 0 ? "bank_control" : "processor_clearing";
        credit = signed > 0 ? "processor_clearing" : "bank_control";
      }
      default -> throw new DomainException(400, "Unsupported economic event");
    }
    long amount = BigInteger.valueOf(signed).abs().longValueExact();
    var balances = balances(merchant);
    Math.addExact(
        balances.getOrDefault(debit, 0L), debit.equals("merchant_payable") ? -amount : amount);
    Math.addExact(
        balances.getOrDefault(credit, 0L), credit.equals("merchant_payable") ? amount : -amount);
    long sequence =
        db.scalar(
            "update merchant set ledger_sequence=ledger_sequence+1,funds_version=funds_version+1"
                + " where id=? returning ledger_sequence",
            Long.class,
            merchant);
    UUID journal = UUID.randomUUID();
    db.exec(
        "insert into journal(id,merchant_id,source_id,purpose,sequence,period_month)"
            + " values(?,?,?,'ECONOMIC',?,?)",
        journal,
        merchant,
        source,
        sequence,
        month);
    db.exec(
        "insert into ledger_entry(id,journal_id,merchant_id,account,debit,credit)"
            + " values(?,?,?,?,?,0),(?,?,?,?,0,?)",
        UUID.randomUUID(),
        journal,
        merchant,
        debit,
        amount,
        UUID.randomUUID(),
        journal,
        merchant,
        credit,
        amount);
  }

  public void requirePostingReady() {
    safety.requirePosting();
  }

  public Map<String, Long> balances(UUID merchant) {
    var result = new LinkedHashMap<String, Long>();
    for (String a :
        List.of("processor_clearing", "merchant_payable", "bank_control", "adjustment_suspense"))
      result.put(a, 0L);
    for (var row :
        db.query(
            "select account,sum(debit::numeric)-sum(credit::numeric) as balance from ledger_entry"
                + " where merchant_id=? group by account",
            merchant)) {
      long value = new java.math.BigDecimal(row.get("balance").toString()).longValueExact();
      result.put(
          row.get("account").toString(),
          row.get("account").equals("merchant_payable") ? Math.negateExact(value) : value);
    }
    return result;
  }
}
