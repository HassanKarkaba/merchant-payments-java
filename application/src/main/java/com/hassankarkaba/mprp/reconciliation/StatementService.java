package com.hassankarkaba.mprp.reconciliation;

import static com.hassankarkaba.mprp.operations.DomainException.require;
import static com.hassankarkaba.mprp.settlements.WorkflowSupport.*;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.*;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StatementService {
  public record Metadata(
      UUID merchant,
      String kind,
      String accountId,
      String statementId,
      String manifestId,
      Instant start,
      Instant end,
      long opening,
      long closing,
      int count,
      long net) {
    public Metadata {
      start = start.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      end = end.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
  }

  private final Db db;
  private final Readiness readiness;
  private final Authz auth;
  private final ProviderClient provider;
  private final TransactionTemplate tx;
  private final com.hassankarkaba.mprp.accounts.SecretCodec secrets;
  private final com.hassankarkaba.mprp.settlements.WorkflowNotice audit;

  public StatementService(
      Db db,
      Authz auth,
      ProviderClient provider,
      PlatformTransactionManager manager,
      com.hassankarkaba.mprp.accounts.SecretCodec secrets,
      com.hassankarkaba.mprp.settlements.WorkflowNotice audit,
      Readiness readiness) {
    this.db = db;
    this.auth = auth;
    this.provider = provider;
    this.tx = new TransactionTemplate(manager);
    this.secrets = secrets;
    this.audit = audit;
    this.readiness = readiness;
  }

  private byte[] encrypt(UUID context, byte[] plaintext) {
    return secrets.encryptBytes(plaintext, "statement:" + context);
  }

  private byte[] decrypt(UUID context, byte[] bytes) {
    try {
      return secrets.decryptBytes(bytes, "statement:" + context);
    } catch (RuntimeException e) {
      throw new DomainException(409, "Staged evidence failed its integrity check");
    }
  }

  private Map<String, Object> metadata(Metadata m) {
    return map(
        "merchant_id",
        m.merchant.toString(),
        "kind",
        m.kind,
        "account_id",
        m.accountId,
        "statement_id",
        m.statementId,
        "manifest_id",
        m.manifestId,
        "period_start",
        m.start,
        "period_end",
        m.end,
        "opening_balance_minor",
        m.opening,
        "closing_balance_minor",
        m.closing,
        "row_count",
        m.count,
        "net_minor",
        m.net);
  }

  public UUID stage(Metadata m, byte[] content) {
    UUID actor = auth.platform("RECONCILIATION");
    require(StatementParser.identifier(m.statementId), 400, "Invalid statement identifier");
    var merchant = db.one("select * from merchant where id=?", m.merchant);
    String account = s(merchant, m.kind.equals("BANK") ? "bank_account_id" : "provider_account_id");
    require(account.equals(m.accountId), 400, "Statement account does not belong to merchant");
    var parsed =
        StatementParser.parse(
            content, m.kind, m.accountId, m.start, m.end, m.count, m.net, m.opening, m.closing);
    String fileHash = CanonicalJson.sha256Bytes(content);
    String metaHash = CanonicalJson.sha256(metadata(m));
    Map<String, Object> outcome =
        tx.execute(
            status -> {
              auth.platform("RECONCILIATION");
              db.lockMerchant(m.merchant);
              var old =
                  db.maybe(
                      "select * from statement_import where merchant_id=? and kind=? and"
                          + " account_id=? and statement_id=? and file_hash=? for update",
                      m.merchant,
                      m.kind,
                      m.accountId,
                      m.statementId,
                      fileHash);
              if (old.isPresent()) {
                var row = old.get();
                if (!s(row, "metadata_hash").equals(metaHash)) {
                  if (s(row, "status").equals("CONFIRMED"))
                    db.exec(
                        "insert into quarantine(id,kind,digest,safe_reason)"
                            + " values(?,'STATEMENT_METADATA_CONFLICT',?,?)",
                        UUID.randomUUID(),
                        metaHash,
                        "Conflicting attempt rejected; confirmed statement retained: "
                            + id(row, "id"));
                  else
                    db.exec(
                        "update statement_import set"
                            + " status='QUARANTINED',errors_json='[\"Conflicting immutable"
                            + " metadata\"]',version=version+1 where id=?",
                        id(row, "id"));
                  return map("error", "Immutable statement metadata conflicts");
                }
                return map("id", id(row, "id"));
              }
              reconciliationReady(db, readiness);
              require(
                  db.scalar(
                          "select (select count(*) from statement_import where merchant_id=? and"
                              + " status in('STAGED','VALIDATED','INVALID') and"
                              + " expires_at>now())+(select count(*) from report_export where"
                              + " merchant_id=? and status in('QUEUED','READY') and"
                              + " expires_at>now())",
                          Long.class,
                          m.merchant,
                          m.merchant)
                      < 10,
                  409,
                  "Too many active imports");
              UUID id = UUID.randomUUID();
              db.exec(
                  "insert into"
                      + " statement_import(id,merchant_id,uploader,kind,account_id,statement_id,manifest_id,period_start,period_end,opening_minor,closing_minor,declared_count,declared_net,file_hash,metadata_hash,content_cipher,parsed_cipher,errors_json,status,expires_at)"
                      + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                  id,
                  m.merchant,
                  actor,
                  m.kind,
                  m.accountId,
                  m.statementId,
                  m.manifestId,
                  Timestamp.from(m.start),
                  Timestamp.from(m.end),
                  m.opening,
                  m.closing,
                  m.count,
                  m.net,
                  fileHash,
                  metaHash,
                  encrypt(id, content),
                  encrypt(id, CanonicalJson.bytes(parsed.rows())),
                  CanonicalJson.string(parsed.errors()),
                  parsed.valid() ? "VALIDATED" : "INVALID",
                  Timestamp.from(Instant.now().plus(Duration.ofHours(24))));
              audit.record(
                  m.merchant,
                  actor,
                  "STATEMENT_STAGED",
                  id,
                  parsed.valid() ? "Whole CSV validated" : "Whole CSV invalid");
              return map("id", id);
            });
    if (outcome.containsKey("error"))
      throw new DomainException(409, outcome.get("error").toString());
    return (UUID) outcome.get("id");
  }

  /** Expiry is committed before returning an error; the caller's error cannot roll it back. */
  public boolean expire(UUID importId) {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              var i = db.one("select * from statement_import where id=? for update", importId);
              if (!Set.of("CONFIRMED", "CANCELLED", "EXPIRED").contains(s(i, "status"))
                  && !Instant.now().isBefore(time(i.get("expires_at")))) {
                db.exec(
                    "update statement_import set"
                        + " status='EXPIRED',content_cipher=null,parsed_cipher=null,version=version+1"
                        + " where id=?",
                    importId);
                return true;
              }
              return s(i, "status").equals("EXPIRED");
            }));
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> preview(UUID importId) {
    auth.platform("RECONCILIATION", "FINANCE_APPROVER", "AUDITOR");
    expire(importId);
    var i = db.one("select * from statement_import where id=?", importId);
    if (i.get("parsed_cipher") == null) return List.of();
    return (List<Map<String, Object>>)
        (List<?>) CanonicalJson.parseAny(decrypt(importId, (byte[]) i.get("parsed_cipher")));
  }

  public void cancel(UUID importId, long expected) {
    UUID actor = auth.platform("RECONCILIATION");
    tx.executeWithoutResult(
        status -> {
          var i = db.one("select * from statement_import where id=? for update", importId);
          version(i, expected);
          require(
              !s(i, "status").equals("CONFIRMED"), 409, "Confirmed evidence cannot be withdrawn");
          db.exec(
              "update statement_import set"
                  + " status='CANCELLED',content_cipher=null,parsed_cipher=null,version=version+1"
                  + " where id=?",
              importId);
          audit.recordAccepted(
              id(i, "merchant_id"),
              actor,
              "STATEMENT_CANCELLED",
              importId,
              "Unconfirmed staging cancelled");
        });
  }

  @SuppressWarnings("unchecked")
  public void confirm(UUID importId, long expected) {
    UUID actor = auth.platform("RECONCILIATION");
    if (expire(importId))
      throw new DomainException(409, "Staged evidence expired; upload it again");
    var initial = db.one("select * from statement_import where id=?", importId);
    if (s(initial, "status").equals("CONFIRMED")) return;
    Map<String, Object> manifest =
        provider.get("/v1/statements/" + s(initial, "manifest_id") + "/");
    String error =
        tx.execute(
            status -> {
              auth.platform("RECONCILIATION");
              UUID merchant = id(initial, "merchant_id");
              db.lockMerchant(merchant);
              var i = db.one("select * from statement_import where id=? for update", importId);
              if (s(i, "status").equals("CONFIRMED")) return null;
              version(i, expected);
              if (!Instant.now().isBefore(time(i.get("expires_at")))) {
                db.exec(
                    "update statement_import set"
                        + " status='EXPIRED',content_cipher=null,parsed_cipher=null,version=version+1"
                        + " where id=?",
                    importId);
                return "Staged evidence expired; upload it again";
              }
              require(
                  s(i, "status").equals("VALIDATED"),
                  409,
                  "Only a completely validated file can be confirmed");
              reconciliationReady(db, readiness);
              List<Map<String, Object>> rows =
                  (List<Map<String, Object>>)
                      (List<?>)
                          CanonicalJson.parseAny(
                              decrypt(importId, (byte[]) i.get("parsed_cipher")));
              try {
                validateManifest(i, manifest, rows);
                for (var row : rows) {
                  var old =
                      db.maybe(
                          "select row_hash from statement_line where merchant_id=? and kind=? and"
                              + " account_id=? and line_id=?",
                          merchant,
                          s(i, "kind"),
                          s(i, "account_id"),
                          s(row, "line_id"));
                  if (old.isPresent())
                    require(
                        s(old.get(), "row_hash").equals(CanonicalJson.sha256(row)),
                        409,
                        "Line identity has changed content");
                }
              } catch (DomainException e) {
                db.exec(
                    "update statement_import set"
                        + " status='QUARANTINED',errors_json=?,version=version+1 where id=?",
                    CanonicalJson.string(List.of(e.getMessage())),
                    importId);
                return e.getMessage();
              }
              int pos = 0;
              for (var row : rows) {
                boolean bank = s(i, "kind").equals("BANK");
                UUID line = UUID.randomUUID();
                String source = bank ? s(row, "transfer_reference") : s(row, "source_id"),
                    reference = bank ? s(row, "transfer_reference") : s(row, "reference");
                db.exec(
                    "insert into"
                        + " statement_line(id,merchant_id,kind,account_id,line_id,source_id,event_type,reference,batch_id,occurred_at,amount_minor,currency,row_hash,canonical_json)"
                        + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?) on"
                        + " conflict(merchant_id,kind,account_id,line_id) do nothing",
                    line,
                    merchant,
                    s(i, "kind"),
                    s(i, "account_id"),
                    s(row, "line_id"),
                    source,
                    bank ? "BANK" : s(row, "event_type"),
                    reference,
                    bank ? "" : s(row, "batch_id"),
                    Timestamp.from(time(row.get(bank ? "booked_at" : "occurred_at"))),
                    n(row, "amount_minor"),
                    "USD",
                    CanonicalJson.sha256(row),
                    CanonicalJson.string(row));
                line =
                    id(
                        db.one(
                            "select id from statement_line where merchant_id=? and kind=? and"
                                + " account_id=? and line_id=?",
                            merchant,
                            s(i, "kind"),
                            s(i, "account_id"),
                            s(row, "line_id")),
                        "id");
                db.exec(
                    "insert into statement_import_row(import_id,line_id,position) values(?,?,?)",
                    importId,
                    line,
                    ++pos);
              }
              db.exec(
                  "update statement_import set"
                      + " status='CONFIRMED',manifest_json=?,confirmed_at=now(),content_cipher=null,parsed_cipher=null,version=version+1"
                      + " where id=?",
                  CanonicalJson.string(manifest),
                  importId);
              audit.record(
                  merchant,
                  actor,
                  "STATEMENT_CONFIRMED",
                  importId,
                  "Exact immutable manifest and all rows confirmed");
              return null;
            });
    if (error != null) throw new DomainException(409, error);
  }

  @SuppressWarnings("unchecked")
  private void validateManifest(
      Map<String, Object> i, Map<String, Object> m, List<Map<String, Object>> rows) {
    require(
        s(m, "kind").equals(s(i, "kind"))
            && s(m, "account_id").equals(s(i, "account_id"))
            && s(m, "statement_id").equals(s(i, "statement_id")),
        409,
        "Manifest identity mismatch");
    String mid = m.containsKey("manifest_id") ? s(m, "manifest_id") : s(m, "id");
    require(mid.equals(s(i, "manifest_id")), 409, "Manifest UUID mismatch");
    require(
        time(m.get("period_start")).equals(time(i.get("period_start")))
            && time(m.get("period_end")).equals(time(i.get("period_end"))),
        409,
        "Manifest interval mismatch");
    require(
        n(m, "row_count") == n(i, "declared_count")
            && n(m, "net_minor") == n(i, "declared_net")
            && n(m, "opening_balance_minor") == n(i, "opening_minor")
            && n(m, "closing_balance_minor") == n(i, "closing_minor"),
        409,
        "Manifest totals mismatch");
    require(
        CanonicalJson.sha256(rows).equals(CanonicalJson.sha256(m.get("rows"))),
        409,
        "Canonical manifest rows differ");
  }
}
