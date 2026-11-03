package com.hassankarkaba.mprp.operations;

import com.hassankarkaba.mprp.accounts.Authz;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class RecoveryController {
  private final Db db;
  private final Authz auth;
  private final RecoveryService recovery;

  public RecoveryController(Db db, Authz auth, RecoveryService recovery) {
    this.db = db;
    this.auth = auth;
    this.recovery = recovery;
  }

  @GetMapping("/operations/recovery/")
  String list(Model m) {
    auth.platform("OPERATIONS", "FINANCE_APPROVER", "AUDITOR");
    m.addAttribute(
        "inventories",
        db.query(
            "select id,maker_id,checker_id,status,created_at from recovery_inventory order by"
                + " created_at desc limit 50"));
    return "recovery";
  }

  @PostMapping("/operations/recovery/propose/")
  ResponseEntity<Void> propose(@RequestParam Map<String, String> form) {
    com.hassankarkaba.mprp.web.Web.fields(form);
    recovery.propose();
    return see();
  }

  @PostMapping("/operations/recovery/{id}/release/")
  ResponseEntity<Void> release(@PathVariable UUID id, @RequestParam Map<String, String> form) {
    com.hassankarkaba.mprp.web.Web.fields(form);
    recovery.release(id);
    return see();
  }

  private ResponseEntity<Void> see() {
    return ResponseEntity.status(303)
        .location(java.net.URI.create("/operations/recovery/"))
        .build();
  }
}
