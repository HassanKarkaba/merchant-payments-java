package com.hassankarkaba.mprp.operations;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.web.Web;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ProviderHistoryController {
  private final Db db;
  private final Authz auth;
  private final ProviderHistoryService histories;

  public ProviderHistoryController(Db db, Authz auth, ProviderHistoryService histories) {
    this.db = db;
    this.auth = auth;
    this.histories = histories;
  }

  @GetMapping("/operations/provider-history/")
  String list(Model model, @RequestParam(defaultValue = "0") int page) {
    auth.platform("OPERATIONS", "RECONCILIATION", "FINANCE_APPROVER", "AUDITOR");
    DomainException.require(page >= 0 && page <= 100000, 400, "Invalid page");
    model.addAttribute(
        "tasks",
        db.query(
            "select"
                + " h.task_id,h.merchant_id,h.operation_id,h.inbox_id,h.status,h.expected_count,h.source_count,h.posting_total,h.posted_count,h.safe_reason,h.created_at,(select"
                + " state from durable_job j where (h.operation_id is not null and"
                + " j.operation_id=h.operation_id) or(h.inbox_id is not null and"
                + " j.kind='PROVIDER_HISTORY' and j.resource_id=h.inbox_id)) as job_state from"
                + " provider_history_task h order by h.created_at desc,h.task_id limit 50 offset ?",
            page * 50));
    model.addAttribute("page", page);
    return "operations/provider-history";
  }

  @GetMapping("/operations/provider-history/{task}/")
  String detail(@PathVariable UUID task, Model model, @RequestParam(defaultValue = "0") int page) {
    auth.platform("OPERATIONS", "RECONCILIATION", "FINANCE_APPROVER", "AUDITOR");
    DomainException.require(page >= 0 && page <= 100000, 400, "Invalid page");
    var history =
        db.one(
            "select"
                + " task_id,merchant_id,status,source_count,expected_count,posted_count,posting_total,safe_reason"
                + " from provider_history_task where task_id=?",
            task);
    model.addAttribute("history", history);
    var stored =
        db.one(
            "select inline_sources,summary_json from provider_history_task where task_id=?", task);
    java.util.List<Map<String, Object>> rows;
    if (Boolean.TRUE.equals(stored.get("inline_sources"))) {
      Object value =
          com.hassankarkaba.mprp.protocol.CanonicalJson.parse(stored.get("summary_json").toString())
              .get("sources");
      java.util.List<?> sources = (java.util.List<?>) value;
      rows = new java.util.ArrayList<>();
      for (int index = Math.min(page * 50, sources.size());
          index < Math.min((page + 1) * 50, sources.size());
          index++) {
        var source = (Map<?, ?>) sources.get(index);
        var evidence = new java.util.LinkedHashMap<String, Object>();
        for (String field :
            java.util.List.of("source_id", "event_type", "sequence", "amount_minor", "occurred_at"))
          evidence.put(field, source.get(field));
        rows.add(evidence);
      }
    } else
      rows =
          db.query(
              "select source_id,event_type,sequence,source_json::jsonb->>'amount_minor' as"
                  + " amount_minor,source_json::jsonb->>'occurred_at' as occurred_at from"
                  + " provider_history_source where task_id=? order by sequence limit 50 offset ?",
              task,
              page * 50);
    return Web.page(model, "Authenticated provider source evidence", rows);
  }

  @PostMapping("/operations/provider-history/{task}/redrive/")
  String redrive(@PathVariable UUID task, @RequestParam Map<String, String> form) {
    Web.fields(form);
    histories.redriveTask(task);
    return Web.redirect("/operations/provider-history/");
  }
}
