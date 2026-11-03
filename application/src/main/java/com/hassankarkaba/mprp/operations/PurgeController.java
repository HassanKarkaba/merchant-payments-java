package com.hassankarkaba.mprp.operations;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.web.Web;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class PurgeController {
  private final Db db;
  private final Authz auth;
  private final PurgeService purge;

  public PurgeController(Db db, Authz auth, PurgeService purge) {
    this.db = db;
    this.auth = auth;
    this.purge = purge;
  }

  @GetMapping("/operations/purges/")
  String list(Model model) {
    auth.platform("OPERATIONS", "FINANCE_APPROVER", "AUDITOR");
    model.addAttribute(
        "purges",
        db.query(
            "select id,merchant_id,maker_id,checker_id,phase,created_at from purge_receipt order by"
                + " created_at desc limit 50"));
    return "purges";
  }

  @PostMapping("/operations/purges/new/")
  ResponseEntity<Void> propose(@RequestParam Map<String, String> f) {
    Web.fields(f, "merchant_id", "backup_inventory");
    purge.propose(UUID.fromString(f.get("merchant_id")), f.get("backup_inventory"));
    return see();
  }

  @PostMapping("/operations/purges/{id}/approve/")
  ResponseEntity<Void> approve(@PathVariable UUID id, @RequestParam Map<String, String> f) {
    Web.fields(f);
    purge.approve(id);
    return see();
  }

  private ResponseEntity<Void> see() {
    return ResponseEntity.status(303).location(java.net.URI.create("/operations/purges/")).build();
  }
}
