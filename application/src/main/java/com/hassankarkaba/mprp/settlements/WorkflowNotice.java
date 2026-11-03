package com.hassankarkaba.mprp.settlements;

import com.hassankarkaba.mprp.accounts.AccountService;
import com.hassankarkaba.mprp.operations.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Required evidence and delivery admission share the protected workflow transaction. */
@Service
public class WorkflowNotice {
  private final Db db;
  private final AuditService audit;
  private final AccountService accounts;
  private final Events events;

  public WorkflowNotice(Db db, AuditService audit, AccountService accounts, Events events) {
    this.db = db;
    this.audit = audit;
    this.accounts = accounts;
    this.events = events;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(UUID merchant, UUID actor, String action, UUID resource, String reason) {
    if (actor != null) {
      var c = db.one("select unpublished_events from system_control where id=true for update");
      com.hassankarkaba.mprp.operations.DomainException.require(
          ((Number) c.get("unpublished_events")).longValue() < 100000,
          503,
          "Notification admission capacity is full");
    }
    recordAccepted(merchant, actor, action, resource, reason);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordAccepted(
      UUID merchant, UUID actor, String action, UUID resource, String reason) {

    audit.record(merchant, actor, action, resource, reason);
    UUID event = UUID.randomUUID();
    for (var u :
        db.query(
            "select distinct u.id,u.email from app_user u left join membership m on m.user_id=u.id"
                + " where u.active and u.verified and u.totp_confirmed and (u.id=? or"
                + " (m.merchant_id=? and m.active and m.role in('OWNER','FINANCE'))) order by u.id",
            actor,
            merchant)) {
      UUID recipient = UUID.fromString(u.get("id").toString());
      UUID notification =
          UUID.nameUUIDFromBytes((event + ":" + recipient).getBytes(StandardCharsets.UTF_8));
      db.exec(
          "insert into notification(id,user_id,merchant_id,title,body) values(?,?,?,?,?)",
          notification,
          recipient,
          db.scalar(
                      "select count(*) from membership where user_id=? and merchant_id=? and"
                          + " active",
                      Long.class,
                      recipient,
                      merchant)
                  > 0
              ? merchant
              : null,
          "Sandbox workflow update",
          action.replace('_', ' ') + ". Sign in to inspect currently authorized records.");
      accounts.mail(
          u.get("email").toString(),
          "Sandbox workflow update",
          "A sandbox record changed. Sign in to inspect currently authorized records.",
          86400);
    }
    events.emit(
        merchant,
        "domain-events.v1",
        merchant,
        "WORKFLOW_CHANGED",
        Map.of("event_id", event.toString(), "resource_id", resource.toString()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordAuditOnly(
      UUID merchant, UUID actor, String action, UUID resource, String reason) {
    audit.record(merchant, actor, action, resource, reason);
  }
}
