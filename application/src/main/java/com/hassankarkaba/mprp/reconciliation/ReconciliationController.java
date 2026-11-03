package com.hassankarkaba.mprp.reconciliation;

import static com.hassankarkaba.mprp.operations.DomainException.require;
import static com.hassankarkaba.mprp.settlements.WorkflowSupport.*;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.*;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import com.hassankarkaba.mprp.web.Web;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class ReconciliationController {
  private final Db db;
  private final Authz auth;
  private final StatementService statements;
  private final MatchService matches;
  private final PeriodService periods;
  private final CorrectionService corrections;
  private final ProviderClient provider;
  private final MatchingJobService matchingJobs;

  public ReconciliationController(
      Db db,
      Authz auth,
      StatementService statements,
      MatchService matches,
      PeriodService periods,
      CorrectionService corrections,
      ProviderClient provider,
      MatchingJobService matchingJobs) {
    this.db = db;
    this.auth = auth;
    this.statements = statements;
    this.matches = matches;
    this.periods = periods;
    this.corrections = corrections;
    this.provider = provider;
    this.matchingJobs = matchingJobs;
  }

  @GetMapping("/m/{merchant}/reconciliation/")
  String merchantReconciliation(@PathVariable UUID merchant) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    return Web.redirect("/operations/reconciliation/?merchant_id=" + merchant);
  }

  @GetMapping("/m/{merchant}/periods/")
  String merchantPeriods(@PathVariable UUID merchant) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    return Web.redirect("/operations/periods/?merchant_id=" + merchant);
  }

  @GetMapping("/operations/imports/new/")
  String newImport(Model model) {
    auth.platform("RECONCILIATION");
    model.addAttribute(
        "merchants",
        db.query(
            "select id,name,provider_account_id,bank_account_id from merchant order by name,id"
                + " limit 100"));
    return "workflows/import-new";
  }

  @PostMapping("/operations/imports/new/")
  String upload(@RequestParam Map<String, String> input, @RequestParam("file") MultipartFile file)
      throws java.io.IOException {
    auth.platform("RECONCILIATION");
    Web.fields(
        input,
        "merchant_id",
        "kind",
        "account_id",
        "statement_id",
        "manifest_id",
        "period_start",
        "period_end",
        "opening_minor",
        "closing_minor",
        "declared_count",
        "declared_net");
    UUID id =
        statements.stage(
            new StatementService.Metadata(
                UUID.fromString(required(input, "merchant_id")),
                required(input, "kind"),
                required(input, "account_id"),
                required(input, "statement_id"),
                required(input, "manifest_id"),
                OffsetDateTime.parse(required(input, "period_start")).toInstant(),
                OffsetDateTime.parse(required(input, "period_end")).toInstant(),
                Long.parseLong(input.get("opening_minor")),
                Long.parseLong(input.get("closing_minor")),
                Integer.parseInt(input.get("declared_count")),
                Long.parseLong(input.get("declared_net"))),
            file.getBytes());
    return Web.redirect("/operations/imports/" + id + "/");
  }

  @GetMapping("/operations/imports/from-simulator/")
  String simulatorImport(Model model) {
    auth.platform("RECONCILIATION");
    var f = new LinkedHashMap<String, String>();
    f.put("merchant_id", "");
    f.put("kind", "PROCESSOR_ECONOMIC");
    f.put("period_start", CanonicalJson.instant(Instant.now().minus(Duration.ofDays(1))));
    f.put("period_end", CanonicalJson.instant(Instant.now()));
    return Web.form(
        model, "Request immutable simulator statement", "/operations/imports/from-simulator/", f);
  }

  @PostMapping("/operations/imports/from-simulator/")
  String simulatorStage(@RequestParam Map<String, String> input) {
    auth.platform("RECONCILIATION");
    Web.fields(input, "merchant_id", "kind", "period_start", "period_end");
    UUID merchant = UUID.fromString(required(input, "merchant_id"));
    db.one("select id from merchant where id=?", merchant);
    String query =
        "/v1/statements/?provider_account_id="
            + merchant
            + "&kind="
            + URLEncoder.encode(input.get("kind"), StandardCharsets.UTF_8)
            + "&period_start="
            + URLEncoder.encode(input.get("period_start"), StandardCharsets.UTF_8)
            + "&period_end="
            + URLEncoder.encode(input.get("period_end"), StandardCharsets.UTF_8);
    var m = provider.get(query);
    require(
        !Boolean.TRUE.equals(m.get("requires_split")),
        409,
        "Interval exceeds 10000 rows; request smaller intervals");
    byte[] content = provider.getBytes("/v1/statements/" + s(m, "manifest_id") + "/csv/");
    UUID id =
        statements.stage(
            new StatementService.Metadata(
                merchant,
                s(m, "kind"),
                s(m, "account_id"),
                s(m, "statement_id"),
                s(m, "manifest_id"),
                time(m.get("period_start")),
                time(m.get("period_end")),
                n(m, "opening_balance_minor"),
                n(m, "closing_balance_minor"),
                Math.toIntExact(n(m, "row_count")),
                n(m, "net_minor")),
            content);
    return Web.redirect("/operations/imports/" + id + "/");
  }

  @GetMapping("/operations/imports/{importId}/")
  String detail(@PathVariable UUID importId, Model model) {
    auth.platform("RECONCILIATION", "FINANCE_APPROVER", "AUDITOR");
    var rows = statements.preview(importId);
    model.addAttribute(
        "statement", safeImport(db.one("select * from statement_import where id=?", importId)));
    model.addAttribute("preview", rows.stream().limit(100).toList());
    return "workflows/import-detail";
  }

  private Map<String, Object> safeImport(Map<String, Object> input) {
    var copy = new LinkedHashMap<>(input);
    copy.remove("content_cipher");
    copy.remove("parsed_cipher");
    copy.remove("manifest_json");
    return copy;
  }

  @PostMapping("/operations/imports/{importId}/{action}/")
  String importAction(
      @PathVariable UUID importId,
      @PathVariable String action,
      @RequestParam Map<String, String> input) {
    Web.fields(input, "version");
    long version = Long.parseLong(input.get("version"));
    if (action.equals("confirm")) statements.confirm(importId, version);
    else if (action.equals("cancel")) statements.cancel(importId, version);
    else throw new DomainException(400, "Unsupported import action");
    return Web.redirect("/operations/imports/" + importId + "/");
  }

  @GetMapping("/operations/reconciliation/")
  String workspace(
      @RequestParam(required = false) UUID merchant_id,
      @RequestParam(defaultValue = "0") int page,
      Model model) {
    auth.platform("RECONCILIATION", "FINANCE_APPROVER", "AUDITOR");
    require(page >= 0 && page <= 10000, 400, "Invalid page");
    model.addAttribute(
        "merchants", db.query("select id,name from merchant order by name,id limit 100"));
    model.addAttribute("merchant", merchant_id);
    if (merchant_id != null) {
      db.one("select id from merchant where id=?", merchant_id);
      model.addAttribute(
          "imports",
          db.query(
              "select id,kind,status,statement_id,created_at from statement_import where"
                  + " merchant_id=? order by created_at desc,id limit 100 offset ?",
              merchant_id,
              page * 100));
      model.addAttribute(
          "lines",
          db.query(
              "select"
                  + " l.id,l.kind,l.line_id,l.source_id,l.event_type,l.amount_minor,l.reference,abs(l.amount_minor::numeric)-coalesce((select"
                  + " sum(a.amount_minor) from allocation a join match_group g on g.id=a.group_id"
                  + " where a.line_id=l.id and g.status='MATCHED'),0) remaining from statement_line"
                  + " l where merchant_id=? order by occurred_at,id limit 100 offset ?",
              merchant_id,
              page * 100));
      model.addAttribute(
          "sources",
          db.query(
              "select id,provider_source_id,event_type,amount_minor,batch_id,recognized from"
                  + " economic_source where merchant_id=? order by occurred_at,id limit 100 offset"
                  + " ?",
              merchant_id,
              page * 100));
      model.addAttribute(
          "groups",
          db.query(
              "select * from match_group where merchant_id=? order by created_at desc,id limit 100"
                  + " offset ?",
              merchant_id,
              page * 100));
      model.addAttribute(
          "exceptions",
          db.query(
              "select * from reconciliation_exception where merchant_id=? order by created_at"
                  + " desc,id limit 100 offset ?",
              merchant_id,
              page * 100));
      model.addAttribute(
          "corrections",
          db.query(
              "select * from correction_proposal where merchant_id=? order by created_at desc,id"
                  + " limit 100 offset ?",
              merchant_id,
              page * 100));
    }
    model.addAttribute("page", page);
    if (merchant_id != null)
      model.addAttribute(
          "matchingRuns",
          db.query(
              "select id,status,matched_groups,examined_lines,safe_message,created_at from"
                  + " matching_run where merchant_id=? order by created_at desc,id limit 100",
              merchant_id));
    model.addAttribute("actionKey", key());
    return "workflows/reconciliation";
  }

  @GetMapping("/operations/matches/new/")
  String matchNew(@RequestParam UUID merchant_id, Model model) {
    auth.platform("RECONCILIATION");
    model.addAttribute("merchant", merchant_id);
    return "workflows/match-new";
  }

  @PostMapping("/operations/matches/new/")
  String match(@RequestParam Map<String, String> input) {
    Web.fields(input, "merchant_id", "allocations", "reason");
    UUID merchant = UUID.fromString(required(input, "merchant_id"));
    require(
        input.get("allocations") != null && input.get("allocations").length() <= 64000,
        400,
        "Allocation form exceeds limit");
    List<MatchService.Allocation> values = new ArrayList<>();
    for (String line : input.get("allocations").strip().split("\\R")) {
      String[] parts = line.split(",", -1);
      require(
          parts.length == 3, 400, "Each allocation row needs line UUID,source UUID,positive cents");
      require(parts[2].strip().matches("[0-9]{1,15}"), 400, "Invalid allocation cents");
      values.add(
          new MatchService.Allocation(
              UUID.fromString(parts[0].strip()),
              UUID.fromString(parts[1].strip()),
              Long.parseLong(parts[2].strip())));
    }
    matches.match(merchant, values, input.get("reason"));
    return Web.redirect("/operations/reconciliation/?merchant_id=" + merchant);
  }

  @PostMapping("/operations/reconciliation/run/")
  String autoMatch(@RequestParam Map<String, String> input) {
    Web.fields(input, "merchant_id");
    UUID merchant = UUID.fromString(required(input, "merchant_id"));
    UUID run = matchingJobs.admit(merchant);
    return Web.redirect("/operations/reconciliation/runs/" + run + "/");
  }

  @PostMapping("/operations/matches/{group}/reverse/")
  String reverse(@PathVariable UUID group, @RequestParam Map<String, String> input) {
    Web.fields(input, "version", "reason");
    auth.platform("RECONCILIATION");
    var g = db.one("select merchant_id from match_group where id=?", group);
    matches.reverse(group, Long.parseLong(input.get("version")), input.get("reason"));
    return Web.redirect("/operations/reconciliation/?merchant_id=" + id(g, "merchant_id"));
  }

  @GetMapping("/operations/exceptions/{exception}/")
  String exception(@PathVariable UUID exception, Model model) {
    auth.platform("RECONCILIATION", "FINANCE_APPROVER", "AUDITOR");
    model.addAttribute(
        "exception", db.one("select * from reconciliation_exception where id=?", exception));
    model.addAttribute(
        "history",
        db.query(
            "select actor,state,reason,created_at,legacy_json from exception_history where"
                + " exception_id=? order by created_at desc,id limit 100",
            exception));
    return "workflows/exception";
  }

  @PostMapping("/operations/exceptions/{exception}/{action}/")
  String exceptionAction(
      @PathVariable UUID exception,
      @PathVariable String action,
      @RequestParam Map<String, String> input) {
    Web.fields(input, "version", "reason");
    if (action.equals("recognize")) {
      auth.platform("RECONCILIATION");
      var e = db.one("select * from reconciliation_exception where id=?", exception);
      corrections.propose(
          id(e, "merchant_id"), UUID.fromString(s(e, "source_reference")), input.get("reason"));
    } else
      matches.exceptionAction(
          exception, Long.parseLong(input.get("version")), action, input.get("reason"));
    return Web.redirect("/operations/exceptions/" + exception + "/");
  }

  @PostMapping("/operations/adjustments/{proposal}/{decision}/")
  String correctionDecision(
      @PathVariable UUID proposal,
      @PathVariable String decision,
      @RequestParam Map<String, String> input) {
    Web.fields(input, "version", "action_key");
    if (decision.equals("adopt")) auth.platform("RECONCILIATION");
    else auth.platform("FINANCE_APPROVER");
    var p = db.one("select merchant_id from correction_proposal where id=?", proposal);
    if (decision.equals("adopt"))
      corrections.adopt(proposal, Long.parseLong(input.get("version")), input.get("action_key"));
    else
      corrections.decide(
          proposal, Long.parseLong(input.get("version")), decision, input.get("action_key"));
    return Web.redirect("/operations/reconciliation/?merchant_id=" + id(p, "merchant_id"));
  }

  @GetMapping("/operations/periods/")
  String periodList(@RequestParam(required = false) UUID merchant_id, Model model) {
    auth.platform("RECONCILIATION", "FINANCE_APPROVER", "AUDITOR");
    model.addAttribute("merchant", merchant_id);
    model.addAttribute(
        "merchants", db.query("select id,name from merchant order by name,id limit 100"));
    model.addAttribute(
        "periods",
        merchant_id == null
            ? List.of()
            : db.query(
                "select * from accounting_period where merchant_id=? order by month desc limit 100",
                merchant_id));
    model.addAttribute(
        "proposals",
        merchant_id == null
            ? List.of()
            : db.query(
                "select * from period_proposal where merchant_id=? order by created_at desc,id"
                    + " limit 100",
                merchant_id));
    model.addAttribute(
        "reports",
        merchant_id == null
            ? List.of()
            : db.query(
                "select * from period_report where merchant_id=? order by month desc,version desc"
                    + " limit 100",
                merchant_id));
    model.addAttribute("actionKey", key());
    return "workflows/periods";
  }

  @GetMapping("/operations/periods/new/")
  String periodNew(Model model) {
    auth.platform("RECONCILIATION");
    return Web.form(
        model,
        "Open a UTC accounting period",
        "/operations/periods/new/",
        new LinkedHashMap<>(
            Map.of(
                "merchant_id",
                "",
                "month",
                LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).toString())));
  }

  @PostMapping("/operations/periods/new/")
  String periodCreate(@RequestParam Map<String, String> input) {
    Web.fields(input, "merchant_id", "month");
    UUID merchant = UUID.fromString(required(input, "merchant_id"));
    periods.create(merchant, LocalDate.parse(required(input, "month")));
    return Web.redirect("/operations/periods/?merchant_id=" + merchant);
  }

  @PostMapping("/operations/periods/{period}/{action}/")
  String periodAction(
      @PathVariable UUID period,
      @PathVariable String action,
      @RequestParam Map<String, String> input) {
    Web.fields(input, "reason", "version", "action_key");
    if (action.equals("close") || action.equals("reopen")) {
      auth.platform("RECONCILIATION");
      var p = db.one("select merchant_id,month from accounting_period where id=?", period);
      periods.propose(
          id(p, "merchant_id"),
          ((java.sql.Date) p.get("month")).toLocalDate(),
          action.toUpperCase(Locale.ROOT),
          input.get("reason"));
      return Web.redirect("/operations/periods/?merchant_id=" + id(p, "merchant_id"));
    }
    if (action.equals("cancel")) auth.platform("RECONCILIATION");
    else auth.platform("FINANCE_APPROVER");
    var p = db.one("select merchant_id from period_proposal where id=?", period);
    if (action.equals("cancel"))
      periods.cancelProposal(
          period,
          Long.parseLong(input.get("version")),
          input.get("action_key"),
          input.get("reason"));
    else
      periods.decide(period, Long.parseLong(input.get("version")), action, input.get("action_key"));
    return Web.redirect("/operations/periods/?merchant_id=" + id(p, "merchant_id"));
  }

  @GetMapping("/operations/reconciliation/runs/{run}/")
  String matchingRun(@PathVariable UUID run, Model model) {
    auth.platform("RECONCILIATION", "FINANCE_APPROVER", "AUDITOR");
    model.addAttribute("run", db.one("select * from matching_run where id=?", run));
    model.addAttribute(
        "job",
        db.one(
            "select state,attempts,lease_until,last_error from durable_job where kind='MATCHING'"
                + " and resource_id=?",
            run));
    return "workflows/matching-run";
  }

  @GetMapping("/operations/reconciliation/runs/{run}/status.json")
  @ResponseBody
  Map<String, Object> matchingStatus(@PathVariable UUID run) {
    auth.platform("RECONCILIATION", "FINANCE_APPROVER", "AUDITOR");
    var r = db.one("select * from matching_run where id=?", run);
    return map(
        "schema_version",
        1,
        "matching_run_id",
        run.toString(),
        "status",
        s(r, "status"),
        "matched_groups",
        n(r, "matched_groups"),
        "examined_lines",
        n(r, "examined_lines"),
        "safe_message",
        s(r, "safe_message"),
        "updated_at",
        CanonicalJson.instant(time(r.get("updated_at"))),
        "inspection_url",
        "/operations/reconciliation/runs/" + run + "/",
        "retry_after_seconds",
        Set.of("QUEUED", "RUNNING").contains(s(r, "status")) ? 3 : 0);
  }

  @GetMapping("/operations/adjustments/{proposal}/")
  String correctionDetail(@PathVariable UUID proposal, Model model) {
    auth.platform("RECONCILIATION", "FINANCE_APPROVER", "AUDITOR");
    model.addAttribute(
        "proposal", db.one("select * from correction_proposal where id=?", proposal));
    model.addAttribute(
        "history",
        db.query(
            "select proposal_version,state,maker,checker,snapshot_json,created_at from"
                + " correction_proposal_history where proposal_id=? order by proposal_version desc"
                + " limit 100",
            proposal));
    model.addAttribute("actionKey", key());
    return "workflows/correction";
  }
}
