package com.hassankarkaba.mprp.reconciliation;

import com.hassankarkaba.mprp.accounts.AccountService;
import com.hassankarkaba.mprp.operations.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded worker-only expiry and dispute reminder discovery survives process restart. */
@Component
public class WorkflowMaintenance {
  private final Db db;
  private final TransactionTemplate tx;
  private final boolean enabled;
  private final AccountService accounts;

  public WorkflowMaintenance(
      Db db,
      PlatformTransactionManager manager,
      AccountService accounts,
      @Value("${mprp.worker:false}") boolean enabled) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.accounts = accounts;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 60000)
  public void run() {
    if (!enabled) return;
    tx.executeWithoutResult(
        status -> {
          db.exec(
              "update statement_import set"
                  + " status='EXPIRED',content_cipher=null,parsed_cipher=null,version=version+1"
                  + " where id in(select id from statement_import where status not"
                  + " in('CONFIRMED','EXPIRED','CANCELLED') and expires_at<=now() order by"
                  + " expires_at limit 100 for update skip locked)");
          db.exec(
              "update statement_import set content_cipher=null,parsed_cipher=null where id"
                  + " in(select id from statement_import where status"
                  + " in('CONFIRMED','CANCELLED','EXPIRED') and (content_cipher is not null or"
                  + " parsed_cipher is not null) limit 100 for update skip locked)");
        });
    for (String window : List.of("48", "24")) {
      String column = window.equals("48") ? "reminder_48_at" : "reminder_24_at";
      for (var candidate :
          db.query(
              "select id,merchant_id from dispute where status in('OPEN','RESPONDED') and "
                  + column
                  + " is null and response_deadline>now() and response_deadline<=now()+interval '"
                  + window
                  + " hours' order by response_deadline,id limit 100")) {
        tx.executeWithoutResult(
            status -> {
              UUID merchant = Db.uuid(candidate, "merchant_id"), dispute = Db.uuid(candidate, "id");
              db.lockMerchant(merchant);
              int claimed =
                  db.exec(
                      "update dispute set "
                          + column
                          + "=now() where id=? and "
                          + column
                          + " is null and status in('OPEN','RESPONDED') and"
                          + " response_deadline>now()",
                      dispute);
              if (claimed == 0) return;
              for (var u :
                  db.query(
                      "select distinct u.id,u.email from app_user u join membership m on"
                          + " m.user_id=u.id where u.active and u.verified and u.totp_confirmed and"
                          + " m.active and m.role in('OWNER','FINANCE') and m.merchant_id=? order"
                          + " by u.id",
                      merchant)) {
                UUID id =
                    UUID.nameUUIDFromBytes(
                        (dispute + ":" + window + ":" + u.get("id"))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                db.exec(
                    "insert into notification(id,user_id,merchant_id,title,body) values(?,?,?,?,?)"
                        + " on conflict(id) do nothing",
                    id,
                    u.get("id"),
                    merchant,
                    "Dispute response reminder",
                    "A dispute response deadline is within "
                        + window
                        + " hours. Inspect authorized dispute records.");
                accounts.mail(
                    u.get("email").toString(),
                    "Sandbox dispute response reminder",
                    "A sandbox dispute deadline is within "
                        + window
                        + " hours. Sign in to inspect authorized records.",
                    86400);
              }
            });
      }
    }
  }
}
