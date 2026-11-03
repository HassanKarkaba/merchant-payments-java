package com.hassankarkaba.mprp.settlements;

import com.hassankarkaba.mprp.disputes.DisputeService;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FinancialWorkflowService {
  private final com.hassankarkaba.mprp.operations.Db db;
  private final SettlementService settlements;
  private final DisputeService disputes;

  public FinancialWorkflowService(
      SettlementService settlements,
      DisputeService disputes,
      com.hassankarkaba.mprp.operations.Db db) {
    this.db = db;
    this.settlements = settlements;
    this.disputes = disputes;
  }

  public void applyProviderResult(
      UUID merchant, String kind, UUID resource, Map<String, Object> result) {
    settlements.apply(merchant, kind, resource, result);
    disputes.apply(merchant, kind, resource, result);
  }

  public void validateDispatch(Map<String, Object> operation) {
    String kind = operation.get("kind").toString();
    if (kind.equals("payout")) settlements.validateDispatch(operation);
    if (kind.equals("dispute_response")) disputes.validateDispatch(operation);
  }

  public void lockDispatchActors(Map<String, Object> operation) {
    if (operation.get("kind").equals("authorize")) return;
    List<UUID> actors = new ArrayList<>();
    actors.add(UUID.fromString(operation.get("actor_id").toString()));
    if (operation.get("kind").equals("payout")) {
      var p =
          db.one(
              "select maker,checker from payout where id=? and merchant_id=?",
              UUID.fromString(operation.get("resource_id").toString()),
              UUID.fromString(operation.get("merchant_id").toString()));
      actors.add(UUID.fromString(p.get("maker").toString()));
      if (p.get("checker") != null) actors.add(UUID.fromString(p.get("checker").toString()));
    }
    WorkflowSupport.lockActors(db, actors.toArray(UUID[]::new));
  }
}
