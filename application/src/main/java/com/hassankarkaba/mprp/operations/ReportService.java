package com.hassankarkaba.mprp.operations;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.accounts.SecretCodec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
  private final Db db;
  private final Authz auth;
  private final Events events;
  private final AuditService audit;
  private final SecretCodec secrets;

  public ReportService(Db db, Authz auth, Events events, AuditService audit, SecretCodec secrets) {
    this.db = db;
    this.auth = auth;
    this.events = events;
    this.audit = audit;
    this.secrets = secrets;
  }

  @Transactional
  public UUID create(UUID merchant, String kind) {
    UUID actor =
        merchant == null ? auth.current() : auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    require(Set.of("payments", "ledger", "account").contains(kind), 400, "Unknown report kind");
    require(merchant != null || kind.equals("account"), 400, "Merchant report requires a merchant");
    if (merchant != null) {
      db.lockMerchant(merchant);
      require(
          db.scalar(
                  "select (select count(*) from statement_import where merchant_id=? and status"
                      + " in('VALIDATED','INVALID'))+(select count(*) from report_export where"
                      + " merchant_id=? and status in('QUEUED','READY') and expires_at>now())",
                  Long.class,
                  merchant,
                  merchant)
              < 10,
          409,
          "Active import/export limit reached");
    } else {
      db.one("select id from app_user where id=? for update", actor);
      require(
          db.scalar(
                  "select count(*) from report_export where user_id=? and merchant_id is null and"
                      + " status in('QUEUED','READY') and expires_at>now()",
                  Long.class,
                  actor)
              < 10,
          409,
          "Account export limit reached");
    }
    var capacity = db.one("select * from system_control where id=true for update");
    require(
        Db.number(capacity, "unfinished_jobs") < 10000
            && Db.number(capacity, "unpublished_events") < 100000,
        503,
        "Export admission backlog is full");
    UUID id = UUID.randomUUID();
    db.exec("update system_control set unfinished_jobs=unfinished_jobs+1 where id=true");
    db.exec(
        "insert into report_export(id,user_id,merchant_id,kind) values(?,?,?,?)",
        id,
        actor,
        merchant,
        kind);
    db.exec(
        "insert into durable_job(id,kind,resource_id,merchant_id) values(?,'EXPORT',?,?)",
        UUID.randomUUID(),
        id,
        merchant);
    events.emit(
        merchant,
        "work.v1",
        merchant == null ? actor : merchant,
        "EXPORT_ACCEPTED",
        Map.of("export_id", id.toString()));
    audit.record(merchant, actor, "export.requested", id, kind);
    return id;
  }

  public Map<String, Object> inspect(UUID id) {
    UUID actor = auth.current();
    var r =
        db.one(
            "select id,user_id,merchant_id,kind,status,cutoff,expires_at,created_at from"
                + " report_export where id=? and user_id=?",
            id,
            actor);
    if (r.get("merchant_id") != null)
      auth.require((UUID) r.get("merchant_id"), "OWNER", "FINANCE", "ANALYST");
    return r;
  }

  public byte[] download(UUID id) {
    inspect(id);
    var r = db.one("select * from report_export where id=?", id);
    require(
        r.get("status").equals("READY")
            && ((Timestamp) r.get("expires_at")).toInstant().isAfter(Instant.now()),
        410,
        "Export unavailable or expired");
    String cipher = new String((byte[]) r.get("content"), StandardCharsets.UTF_8);
    byte[] bytes = secrets.decrypt(cipher, "export:" + id).getBytes(StandardCharsets.UTF_8);
    require(
        com.hassankarkaba.mprp.protocol.CanonicalJson.digest(bytes).equals(r.get("digest")),
        409,
        "Export integrity mismatch");
    return bytes;
  }

  public boolean authority(Map<String, Object> r) {
    return db.scalar(
                "select count(*) from app_user where id=? and active and verified and"
                    + " totp_confirmed",
                Long.class,
                r.get("user_id"))
            == 1
        && (r.get("merchant_id") == null
            || db.scalar(
                    "select count(*) from membership where user_id=? and merchant_id=? and active"
                        + " and role in('OWNER','FINANCE','ANALYST')",
                    Long.class,
                    r.get("user_id"),
                    r.get("merchant_id"))
                == 1);
  }

  public static String csv(String value) {
    if (value == null) value = "";
    if (!value.isEmpty() && "=+-@\t\r\n".indexOf(value.charAt(0)) >= 0) value = "'" + value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }
}
