package com.hassankarkaba.mprp.disputes;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.*;
import com.hassankarkaba.mprp.web.Web;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class DisputeController {
  private final Db db;
  private final Authz auth;
  private final DisputeService service;

  public DisputeController(Db db, Authz auth, DisputeService service) {
    this.db = db;
    this.auth = auth;
    this.service = service;
  }

  @GetMapping("/m/{merchant}/disputes/")
  String list(
      @PathVariable UUID merchant,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String status,
      Model model) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    DomainException.require(
        page >= 0 && page <= 10000 && size >= 1 && size <= 100 && status.length() <= 16,
        400,
        "Invalid list filters");
    model.addAttribute("title", "Disputes and response deadlines");
    model.addAttribute(
        "rows",
        db.query(
            "select id,payment_id,amount_minor,status,response_deadline,version from dispute where"
                + " merchant_id=? and (?='' or status=?) order by created_at desc,id limit ? offset"
                + " ?",
            merchant,
            status,
            status,
            size,
            page * size));
    model.addAttribute("merchant", merchant);
    model.addAttribute("listUrl", "/m/" + merchant + "/disputes/");
    model.addAttribute("detailBase", "/m/" + merchant + "/disputes/");
    model.addAttribute("page", page);
    model.addAttribute("size", size);
    model.addAttribute("statusFilter", status);
    return "workflows/records";
  }

  @GetMapping("/m/{merchant}/disputes/{dispute}/")
  String detail(@PathVariable UUID merchant, @PathVariable UUID dispute, Model model) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    model.addAttribute("merchant", merchant);
    model.addAttribute(
        "dispute", db.one("select * from dispute where id=? and merchant_id=?", dispute, merchant));
    model.addAttribute("actionKey", com.hassankarkaba.mprp.settlements.WorkflowSupport.key());
    return "workflows/dispute";
  }

  @PostMapping("/m/{merchant}/disputes/{dispute}/respond/")
  String respond(
      @PathVariable UUID merchant,
      @PathVariable UUID dispute,
      @RequestParam Map<String, String> input) {
    Web.fields(input, "response", "action_key");
    UUID operation =
        service.respond(merchant, dispute, input.get("response"), input.get("action_key"));
    return Web.redirect("/m/" + merchant + "/operations/" + operation + "/");
  }
}
