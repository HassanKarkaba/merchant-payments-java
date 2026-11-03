package com.hassankarkaba.mprp.merchants;

import com.hassankarkaba.mprp.accounts.AccountController;
import com.hassankarkaba.mprp.accounts.AccountRules;
import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.Db;
import com.hassankarkaba.mprp.operations.DomainException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class MerchantController {
  private final Db db;
  private final Authz authz;
  private final MerchantService merchants;

  public MerchantController(Db db, Authz authz, MerchantService merchants) {
    this.db = db;
    this.authz = authz;
    this.merchants = merchants;
  }

  @GetMapping("/m/{merchant}/")
  String overview(@PathVariable UUID merchant, Model model) {
    authz.require(merchant);
    model.addAttribute("merchant", db.one("select * from merchant where id=?", merchant));
    return "merchants/overview";
  }

  @GetMapping("/m/{merchant}/settings/")
  String settings(@PathVariable UUID merchant, Model model) {
    authz.require(merchant, "OWNER");
    var m = db.one("select * from merchant where id=?", merchant);
    model.addAttribute("values", m);
    model.addAttribute("hidden", Map.of("expected_version", m.get("version")));
    return AccountController.form(
        model, "Merchant settings", "Save settings", Map.of("name", "text", "timezone", "text"));
  }

  @PostMapping("/m/{merchant}/settings/")
  Object settings(@PathVariable UUID merchant, @RequestParam Map<String, String> fields) {
    merchants.settings(merchant, fields);
    return AccountController.redirect("/m/" + merchant + "/settings/");
  }

  @GetMapping("/m/{merchant}/team/")
  String team(@PathVariable UUID merchant, Model model) {
    authz.require(merchant, "OWNER");
    model.addAttribute("merchant", db.one("select * from merchant where id=?", merchant));
    model.addAttribute(
        "members",
        db.query(
            "select x.*,u.email from membership x join app_user u on u.id=x.user_id where"
                + " x.merchant_id=? order by u.email",
            merchant));
    model.addAttribute(
        "invitations",
        db.query(
            "select id,email,role,expires_at,consumed_at,revoked_at from account_token where"
                + " merchant_id=? and kind='INVITE' order by created_at desc limit 100",
            merchant));
    return "merchants/team";
  }

  @PostMapping("/m/{merchant}/team/invite/")
  Object invite(@PathVariable UUID merchant, @RequestParam Map<String, String> fields) {
    merchants.invite(merchant, fields);
    return AccountController.redirect("/m/" + merchant + "/team/");
  }

  @PostMapping("/m/{merchant}/team/{member}/change/")
  Object change(
      @PathVariable UUID merchant,
      @PathVariable UUID member,
      @RequestParam Map<String, String> fields) {
    merchants.changeMember(merchant, member, fields);
    return AccountController.redirect("/m/" + merchant + "/team/");
  }

  @PostMapping("/m/{merchant}/team/invitations/{invitation}/revoke/")
  Object revoke(
      @PathVariable UUID merchant,
      @PathVariable UUID invitation,
      @RequestParam Map<String, String> fields) {
    AccountRules.fields(fields.keySet());
    merchants.revokeInvite(merchant, invitation);
    return AccountController.redirect("/m/" + merchant + "/team/");
  }

  @GetMapping("/accounts/invitations/{token}/")
  String invitation(@PathVariable String token, Model model) {
    authz.current();
    model.addAttribute(
        "message",
        "Accept the invitation for your exact verified email. The issuing owner must still be"
            + " authorized.");
    return AccountController.form(model, "Accept invitation", "Accept invitation", Map.of());
  }

  @PostMapping("/accounts/invitations/{token}/")
  Object invitation(@PathVariable String token, @RequestParam Map<String, String> fields) {
    AccountRules.fields(fields.keySet());
    merchants.accept(token);
    return AccountController.redirect("/accounts/profile/");
  }

  @PostMapping("/m/{merchant}/request-close/")
  Object close(@PathVariable UUID merchant, @RequestParam Map<String, String> fields) {
    AccountRules.fields(fields.keySet());
    merchants.requestClose(merchant);
    return AccountController.redirect("/m/" + merchant + "/");
  }

  @GetMapping("/operations/merchants/")
  String queue(Model model, @RequestParam(defaultValue = "0") int page) {
    authz.platform("OPERATIONS");
    if (page < 0 || page > 100000) throw new DomainException(400, "Invalid page.");
    model.addAttribute(
        "merchants",
        db.query("select * from merchant order by created_at,id limit 50 offset ?", page * 50));
    model.addAttribute("page", page);
    return "merchants/queue";
  }

  @PostMapping("/operations/merchants/{merchant}/{action}/")
  Object lifecycle(
      @PathVariable UUID merchant,
      @PathVariable String action,
      @RequestParam Map<String, String> fields) {
    AccountRules.fields(fields.keySet(), "expected_version");
    long version;
    try {
      version = Long.parseLong(fields.get("expected_version"));
    } catch (Exception e) {
      throw new DomainException(400, "Expected version is required.");
    }
    merchants.lifecycle(merchant, action, version);
    return AccountController.redirect("/operations/merchants/");
  }
}
