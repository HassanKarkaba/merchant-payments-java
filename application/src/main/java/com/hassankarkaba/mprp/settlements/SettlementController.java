package com.hassankarkaba.mprp.settlements;

import static com.hassankarkaba.mprp.settlements.WorkflowSupport.*;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.*;
import com.hassankarkaba.mprp.protocol.Money;
import com.hassankarkaba.mprp.web.Web;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class SettlementController {
  private final Db db;
  private final Authz auth;
  private final SettlementService service;
  private final ProviderClient provider;

  public SettlementController(
      Db db, Authz auth, SettlementService service, ProviderClient provider) {
    this.db = db;
    this.auth = auth;
    this.service = service;
    this.provider = provider;
  }

  @GetMapping({"/m/{merchant}/beneficiaries/", "/m/{merchant}/payouts/"})
  String list(
      @PathVariable UUID merchant,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String beneficiary_status,
      @RequestParam(defaultValue = "") String payout_status,
      Model model) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    DomainException.require(
        page >= 0
            && page <= 10000
            && size >= 1
            && size <= 100
            && beneficiary_status.length() <= 32
            && payout_status.length() <= 32,
        400,
        "Invalid list filters");
    model.addAttribute("merchant", merchant);
    model.addAttribute(
        "beneficiaries",
        db.query(
            "select * from beneficiary where merchant_id=? and (?='' or status=?) order by"
                + " created_at desc,id limit ? offset ?",
            merchant,
            beneficiary_status,
            beneficiary_status,
            size,
            page * size));
    model.addAttribute(
        "payouts",
        db.query(
            "select * from payout where merchant_id=? and (?='' or status=?) order by created_at"
                + " desc,id limit ? offset ?",
            merchant,
            payout_status,
            payout_status,
            size,
            page * size));
    model.addAttribute("available", service.available(merchant));
    model.addAttribute("actionKey", key());
    model.addAttribute("page", page);
    model.addAttribute("size", size);
    model.addAttribute("beneficiaryFilter", beneficiary_status);
    model.addAttribute("payoutFilter", payout_status);
    return "workflows/finance";
  }

  @GetMapping("/m/{merchant}/beneficiaries/new/")
  String beneficiaryNew(@PathVariable UUID merchant, Model model) {
    auth.require(merchant, "OWNER", "FINANCE");
    String token = "";
    try {
      token = s(provider.get("/v1/accounts/" + merchant + "/"), "beneficiary_token");
    } catch (ProviderClient.ProviderUnavailable unavailable) {
      model.addAttribute(
          "message",
          "A simulator-issued destination becomes available after this merchant has completed its"
              + " first provider operation. You can also enter an existing synthetic beneficiary"
              + " token.");
    }
    return Web.form(
        model,
        "Propose a synthetic beneficiary",
        "/m/" + merchant + "/beneficiaries/new/",
        new LinkedHashMap<>(Map.of("token", token)));
  }

  @PostMapping("/m/{merchant}/beneficiaries/new/")
  String beneficiarySave(@PathVariable UUID merchant, @RequestParam Map<String, String> input) {
    Web.fields(input, "token");
    auth.require(merchant, "OWNER", "FINANCE");
    String token = input.get("token");
    DomainException.require(
        token != null && token.matches("acct_demo_[0-9a-f]{32}"),
        400,
        "Use a simulator-issued beneficiary identifier");
    String hex = token.substring(10);
    UUID destination =
        UUID.fromString(
            hex.substring(0, 8)
                + "-"
                + hex.substring(8, 12)
                + "-"
                + hex.substring(12, 16)
                + "-"
                + hex.substring(16, 20)
                + "-"
                + hex.substring(20));
    var account = provider.get("/v1/accounts/" + destination + "/");
    DomainException.require(
        token.equals(account.get("beneficiary_token"))
            && !Boolean.TRUE.equals(account.get("retired")),
        400,
        "Synthetic beneficiary is not issued and active");
    service.proposeBeneficiary(merchant, input.get("token"));
    return Web.redirect("/m/" + merchant + "/beneficiaries/");
  }

  @PostMapping("/m/{merchant}/beneficiaries/{proposal}/approve/")
  String beneficiaryApprove(
      @PathVariable UUID merchant,
      @PathVariable UUID proposal,
      @RequestParam Map<String, String> input) {
    Web.fields(input, "version", "action_key");
    service.approveBeneficiary(
        merchant, proposal, Long.parseLong(input.get("version")), input.get("action_key"));
    return Web.redirect("/m/" + merchant + "/beneficiaries/");
  }

  @GetMapping("/m/{merchant}/payouts/new/")
  String payoutNew(@PathVariable UUID merchant, Model model) {
    auth.require(merchant, "OWNER", "FINANCE");
    var fields = new LinkedHashMap<String, String>();
    fields.put("beneficiary_id", "");
    fields.put("amount", "");
    fields.put("reason", "");
    return Web.form(
        model, "Propose payout in simulated USD", "/m/" + merchant + "/payouts/new/", fields);
  }

  @PostMapping("/m/{merchant}/payouts/new/")
  String payoutSave(@PathVariable UUID merchant, @RequestParam Map<String, String> input) {
    Web.fields(input, "beneficiary_id", "amount", "reason");
    UUID payout =
        service.proposePayout(
            merchant,
            UUID.fromString(required(input, "beneficiary_id")),
            Money.parse(input.get("amount"), 1, 100000000),
            input.get("reason"));
    return Web.redirect("/m/" + merchant + "/payouts/" + payout + "/");
  }

  @GetMapping("/m/{merchant}/payouts/{payout}/")
  String payout(@PathVariable UUID merchant, @PathVariable UUID payout, Model model) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    model.addAttribute("merchant", merchant);
    model.addAttribute(
        "payout", db.one("select * from payout where merchant_id=? and id=?", merchant, payout));
    model.addAttribute(
        "versions",
        db.query(
            "select proposal_version,snapshot_json,created_at from payout_proposal_version where"
                + " payout_id=? order by proposal_version",
            payout));
    model.addAttribute("actionKey", key());
    return "workflows/payout";
  }

  @PostMapping("/m/{merchant}/payouts/{payout}/approve/")
  String payoutApprove(
      @PathVariable UUID merchant,
      @PathVariable UUID payout,
      @RequestParam Map<String, String> input) {
    Web.fields(input, "version", "action_key", "proposal_hash");
    service.approvePayout(
        merchant,
        payout,
        Long.parseLong(input.get("version")),
        input.get("action_key"),
        input.get("proposal_hash"));
    return Web.redirect("/m/" + merchant + "/payouts/" + payout + "/");
  }

  @PostMapping("/m/{merchant}/payouts/{payout}/cancel/")
  String payoutCancel(
      @PathVariable UUID merchant,
      @PathVariable UUID payout,
      @RequestParam Map<String, String> input) {
    Web.fields(input, "version", "action_key");
    service.cancelPayout(
        merchant, payout, Long.parseLong(input.get("version")), input.get("action_key"));
    return Web.redirect("/m/" + merchant + "/payouts/" + payout + "/");
  }

  @GetMapping("/m/{merchant}/settlements/")
  String batches(
      @PathVariable UUID merchant,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      Model model) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    DomainException.require(
        page >= 0 && page <= 10000 && size >= 1 && size <= 100, 400, "Invalid page");
    model.addAttribute("title", "Settlement batches");
    model.addAttribute(
        "rows",
        db.query(
            "select id,provider_id,net_minor,transfer_reference,occurred_at from settlement_batch"
                + " where merchant_id=? order by occurred_at desc,id limit ? offset ?",
            merchant,
            size,
            page * size));
    model.addAttribute("merchant", merchant);
    model.addAttribute("listUrl", "/m/" + merchant + "/settlements/");
    model.addAttribute("detailBase", "/m/" + merchant + "/settlements/");
    model.addAttribute("page", page);
    model.addAttribute("size", size);
    return "workflows/records";
  }

  @GetMapping("/m/{merchant}/settlements/{batch}/")
  String batch(@PathVariable UUID merchant, @PathVariable UUID batch, Model model) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    return Web.page(
        model,
        "Settlement evidence",
        List.of(
            db.one(
                "select * from settlement_batch where merchant_id=? and id=?", merchant, batch)));
  }

  @GetMapping("/m/{merchant}/reports/close/{report}/")
  String closeReport(@PathVariable UUID merchant, @PathVariable UUID report, Model model) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    return Web.page(
        model,
        "Retained accounting close report",
        List.of(
            db.one(
                "select id,month,version,valid,snapshot_hash,snapshot_json,created_at from"
                    + " period_report where merchant_id=? and id=?",
                merchant,
                report)));
  }

  @PostMapping("/m/{merchant}/payouts/{payout}/revise/")
  String payoutRevise(
      @PathVariable UUID merchant,
      @PathVariable UUID payout,
      @RequestParam Map<String, String> input) {
    Web.fields(input, "version", "action_key", "beneficiary_id");
    service.revisePayout(
        merchant,
        payout,
        UUID.fromString(required(input, "beneficiary_id")),
        Long.parseLong(input.get("version")),
        input.get("action_key"));
    return Web.redirect("/m/" + merchant + "/payouts/" + payout + "/");
  }
}
