package com.hassankarkaba.mprp.operations;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.accounting.LedgerService;
import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.protocol.*;
import com.hassankarkaba.mprp.web.Web;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class OperationsController {
  private final Db db;
  private final Authz auth;
  private final OperationService ops;
  private final LedgerService ledger;
  private final Readiness ready;
  private final AuditService audit;
  private final ReportService reports;
  private final CallbackService callbacks;
  private final TransactionTemplate tx;
  private final ServiceKeys keys;

  public OperationsController(
      Db db,
      Authz auth,
      OperationService ops,
      LedgerService ledger,
      Readiness ready,
      AuditService audit,
      ReportService reports,
      CallbackService callbacks,
      org.springframework.transaction.PlatformTransactionManager manager,
      ServiceKeys keys) {
    this.db = db;
    this.auth = auth;
    this.ops = ops;
    this.ledger = ledger;
    this.ready = ready;
    this.audit = audit;
    this.reports = reports;
    this.callbacks = callbacks;
    this.tx = new TransactionTemplate(manager);
    this.keys = keys;
  }

  @GetMapping("/health/live/")
  @ResponseBody
  Map<String, Object> live() {
    return Map.of("live", true, "sandbox", true);
  }

  @GetMapping("/health/ready/")
  @ResponseBody
  Map<String, Object> readiness() {
    return ready.capabilities();
  }

  @GetMapping("/m/{merchant}/operations/{operation}/status.json")
  @ResponseBody
  Map<String, Object> status(@PathVariable UUID merchant, @PathVariable UUID operation) {
    return ops.status(merchant, operation);
  }

  @GetMapping("/m/{merchant}/operations/{operation}/")
  String operation(@PathVariable UUID merchant, @PathVariable UUID operation, Model m) {
    m.addAttribute("operation", ops.status(merchant, operation));
    m.addAttribute("merchant", merchant);
    m.addAttribute("action_key", com.hassankarkaba.mprp.payments.PaymentService.secret());
    m.addAttribute(
        "proposal_hash",
        db.one(
                "select fingerprint from operation where id=? and merchant_id=?",
                operation,
                merchant)
            .get("fingerprint"));
    return "operation";
  }

  @PostMapping("/m/{merchant}/operations/{operation}/cancel/")
  ResponseEntity<Void> cancel(
      @PathVariable UUID merchant,
      @PathVariable UUID operation,
      @RequestParam Map<String, String> f) {
    Web.fields(f, "action_key", "expected_version", "proposal_hash");
    ops.cancel(
        merchant,
        operation,
        auth.current(),
        f.get("action_key"),
        Long.parseLong(f.get("expected_version")),
        f.get("proposal_hash"));
    return see("/m/" + merchant + "/operations/" + operation + "/");
  }

  @GetMapping("/m/{merchant}/balances/")
  String balances(@PathVariable UUID merchant, Model m) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    m.addAttribute("balances", ledger.balances(merchant));
    m.addAttribute("merchant", merchant);
    return "balances";
  }

  @GetMapping("/m/{merchant}/ledger/")
  String ledger(@PathVariable UUID merchant, @RequestParam(defaultValue = "0") int page, Model m) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    require(page >= 0 && page <= 100000, 400, "Invalid page");
    return Web.page(
        m,
        "Immutable journal",
        db.query(
            "select j.id,j.sequence,j.period_month,s.event_type,s.amount_minor,s.provider_source_id"
                + " from journal j join economic_source s on s.id=j.source_id where j.merchant_id=?"
                + " order by j.sequence desc limit 50 offset ?",
            merchant,
            page * 50));
  }

  @GetMapping("/operations/status/")
  String system(Model m) {
    auth.platform("OPERATIONS", "AUDITOR");
    m.addAttribute("capabilities", ready.capabilities());
    m.addAttribute("audit_valid", audit.verify());
    m.addAttribute(
        "unknown",
        db.query(
            "select o.id,o.merchant_id,o.kind,o.status,o.reason,o.created_at,j.id as job_id from"
                + " operation o join durable_job j on j.operation_id=o.id where o.status='UNKNOWN'"
                + " order by o.created_at limit 100"));
    m.addAttribute(
        "quarantine",
        db.query(
            "select id,kind,safe_reason,created_at from quarantine order by created_at desc limit"
                + " 50"));
    return "system";
  }

  @GetMapping("/operations/audit/")
  String audit(@RequestParam(defaultValue = "0") int page, Model m) {
    auth.platform("OPERATIONS", "AUDITOR");
    require(page >= 0 && page <= 100000, 400, "Invalid page");
    return Web.page(
        m,
        "Audit history",
        db.query(
            "select sequence,merchant_id,actor_id,action,resource_id,reason,created_at,digest from"
                + " audit_event order by created_at desc,id limit 50 offset ?",
            page * 50));
  }

  @GetMapping("/notifications/")
  String notifications(Model m) {
    UUID actor = auth.current();
    m.addAttribute(
        "notifications",
        db.query(
            "select n.id,n.title,n.body,n.read_at,n.created_at from notification n where"
                + " n.user_id=? and (n.merchant_id is null or exists(select 1 from membership m"
                + " where m.user_id=? and m.merchant_id=n.merchant_id and m.active)) order by"
                + " n.created_at desc limit 100",
            actor,
            actor));
    return "notifications";
  }

  @PostMapping("/notifications/{id}/read/")
  ResponseEntity<Void> read(@PathVariable UUID id) {
    UUID actor = auth.current();
    require(
        db.exec(
                "update notification set read_at=coalesce(read_at,now()) where id=? and user_id=?"
                    + " and (merchant_id is null or exists(select 1 from membership where user_id=?"
                    + " and merchant_id=notification.merchant_id and active))",
                id,
                actor,
                actor)
            == 1,
        404,
        "Notification not found");
    return see("/notifications/");
  }

  @GetMapping("/m/{merchant}/reports/")
  String reports(@PathVariable UUID merchant, Model m) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    m.addAttribute("merchant", merchant);
    m.addAttribute(
        "exports",
        db.query(
            "select id,kind,status,created_at,expires_at from report_export where user_id=? and"
                + " merchant_id=? order by created_at desc limit 100",
            auth.current(),
            merchant));
    return "reports";
  }

  @PostMapping("/m/{merchant}/exports/")
  ResponseEntity<Void> create(@PathVariable UUID merchant, @RequestParam Map<String, String> f) {
    Web.fields(f, "kind");
    UUID id = reports.create(merchant, f.get("kind"));
    return see("/exports/" + id + "/");
  }

  @PostMapping("/accounts/export/")
  ResponseEntity<Void> accountExport(@RequestParam Map<String, String> f) {
    Web.fields(f);
    return see("/exports/" + reports.create(null, "account") + "/");
  }

  @GetMapping("/exports/{id}/")
  String export(@PathVariable UUID id, Model m) {
    m.addAttribute("export", reports.inspect(id));
    return "export";
  }

  @GetMapping("/exports/{id}/download/")
  ResponseEntity<byte[]> download(@PathVariable UUID id) {
    byte[] bytes = reports.download(id);
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"sandbox-export.csv\"")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body(bytes);
  }

  @PostMapping("/callbacks/processor/v1/")
  @ResponseBody
  ResponseEntity<Map<String, Object>> callback(
      jakarta.servlet.http.HttpServletRequest req, @RequestBody byte[] body) {
    require(body.length <= 262144, 413, "Callback too large");
    String key = req.getHeader("X-Mprp-Key-Id"),
        timestamp = req.getHeader("X-Mprp-Timestamp"),
        nonce = req.getHeader("X-Mprp-Nonce"),
        signature = req.getHeader("X-Mprp-Signature");
    Instant now = Instant.now();
    String path =
        req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
    require(
        keys.verificationSecret(key, now)
            .map(
                secret ->
                    HmacAuth.verify(secret, "POST", path, timestamp, nonce, body, signature, now))
            .orElse(false),
        401,
        "Callback authentication failed");
    UUID id =
        tx.execute(
            s -> {
              require(
                  db.exec(
                          "insert into callback_nonce(key_id,nonce,expires_at)"
                              + " values(?,?,now()+interval '10 minutes') on conflict do nothing",
                          key,
                          nonce)
                      == 1,
                  401,
                  "Callback replay rejected");
              return callbacks.admit(CanonicalJson.parse(body));
            });
    return ResponseEntity.accepted().body(Map.of("accepted", true, "event_id", id.toString()));
  }

  private ResponseEntity<Void> see(String path) {
    return ResponseEntity.status(303).location(java.net.URI.create(path)).build();
  }
}
