package com.hassankarkaba.mprp.operations;

import static com.hassankarkaba.mprp.operations.DomainException.require;
import static com.hassankarkaba.mprp.settlements.WorkflowSupport.*;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.web.Web;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class SyntheticControlsController {
  private final Db db;
  private final Authz auth;
  private final OperationService ops;

  public SyntheticControlsController(Db db, Authz auth, OperationService ops) {
    this.db = db;
    this.auth = auth;
    this.ops = ops;
  }

  @GetMapping("/operations/sandbox/")
  String controls(Model model) {
    auth.platform("OPERATIONS");
    model.addAttribute(
        "merchants", db.query("select id,name from merchant order by name,id limit 100"));
    model.addAttribute(
        "payments",
        db.query(
            "select id,merchant_id,captured_minor,status from payment order by created_at desc,id"
                + " limit 100"));
    model.addAttribute("actionKey", key());
    return "workflows/sandbox";
  }

  @PostMapping("/operations/sandbox/settlements/flush/")
  String flush(@RequestParam Map<String, String> input) {
    UUID actor = auth.platform("OPERATIONS");
    Web.fields(input, "merchant_id", "cutoff_identity", "action_key");
    UUID merchant = UUID.fromString(required(input, "merchant_id"));
    String cutoff = text(input.get("cutoff_identity"), 128);
    UUID resource =
        UUID.nameUUIDFromBytes(
            (merchant + ":" + cutoff).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID operation =
        ops.admit(
            merchant,
            actor,
            "flush",
            resource,
            input.get("action_key"),
            map("cutoff_identity", cutoff));
    return Web.redirect("/m/" + merchant + "/operations/" + operation + "/");
  }

  @PostMapping("/operations/sandbox/payments/{payment}/open-dispute/")
  String openDispute(@PathVariable UUID payment, @RequestParam Map<String, String> input) {
    UUID actor = auth.platform("OPERATIONS");
    Web.fields(input, "amount_minor", "action_key");
    var p = db.one("select merchant_id,captured_minor from payment where id=?", payment);
    require(
        input.get("amount_minor") != null && input.get("amount_minor").matches("[0-9]{1,9}"),
        400,
        "Use positive synthetic dispute cents");
    long amount = Long.parseLong(input.get("amount_minor"));
    require(
        amount >= 1 && amount <= n(p, "captured_minor"),
        400,
        "Dispute principal exceeds captured amount");
    UUID merchant = id(p, "merchant_id");
    UUID operation =
        ops.admit(
            merchant,
            actor,
            "open_dispute",
            payment,
            input.get("action_key"),
            map("payment_id", payment.toString(), "amount_minor", amount));
    return Web.redirect("/m/" + merchant + "/operations/" + operation + "/");
  }

  @PostMapping("/operations/sandbox/corrections/new/")
  String correction(@RequestParam Map<String, String> input) {
    UUID actor = auth.platform("OPERATIONS");
    Web.fields(input, "merchant_id", "amount_minor", "event_type", "reason", "action_key");
    UUID merchant = UUID.fromString(required(input, "merchant_id"));
    require(
        Set.of("CORRECTION_CREDIT", "CORRECTION_DEBIT")
            .contains(Objects.toString(input.get("event_type"), "")),
        400,
        "Choose an exact correction type");
    require(
        input.get("amount_minor") != null && input.get("amount_minor").matches("[0-9]{1,9}"),
        400,
        "Use absolute synthetic cents");
    long amount = Long.parseLong(input.get("amount_minor"));
    require(amount >= 1 && amount <= 100000000, 400, "Correction amount out of range");
    String reason = text(input.get("reason"), 2000);
    UUID resource =
        UUID.nameUUIDFromBytes(
            (merchant + ":" + actor + ":" + input.get("action_key"))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID operation =
        ops.admit(
            merchant,
            actor,
            "correction",
            resource,
            input.get("action_key"),
            map(
                "event_type",
                input.get("event_type"),
                "amount_minor",
                input.get("event_type").equals("CORRECTION_DEBIT") ? -amount : amount,
                "reason",
                reason));
    return Web.redirect("/m/" + merchant + "/operations/" + operation + "/");
  }
}
