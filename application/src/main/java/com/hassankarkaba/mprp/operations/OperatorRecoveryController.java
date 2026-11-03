package com.hassankarkaba.mprp.operations;

import com.hassankarkaba.mprp.web.Web;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
public class OperatorRecoveryController {
  private final OperatorRecoveryService recovery;

  public OperatorRecoveryController(OperatorRecoveryService recovery) {
    this.recovery = recovery;
  }

  @PostMapping("/operations/jobs/{job}/redrive/")
  ResponseEntity<Void> job(@PathVariable UUID job, @RequestParam Map<String, String> f) {
    Web.fields(f, "reason");
    recovery.job(job, f.get("reason"));
    return see();
  }

  @PostMapping("/operations/quarantine/{id}/redrive/")
  ResponseEntity<Void> quarantine(@PathVariable UUID id, @RequestParam Map<String, String> f) {
    Web.fields(f, "reason");
    recovery.quarantine(id, f.get("reason"));
    return see();
  }

  private ResponseEntity<Void> see() {
    return ResponseEntity.status(303).location(java.net.URI.create("/operations/status/")).build();
  }
}
