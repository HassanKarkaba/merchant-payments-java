package com.hassankarkaba.mprp.operations;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@org.springframework.core.annotation.Order(0)
public class RuntimeCoordinator implements ApplicationRunner {
  private final Db db;
  private final ProviderClient provider;
  private final CallbackService callbacks;
  private final TransactionTemplate tx;
  private final UUID environment;
  private final boolean enabled;
  private final String command;

  public RuntimeCoordinator(
      Db db,
      ProviderClient provider,
      CallbackService callbacks,
      org.springframework.transaction.PlatformTransactionManager manager,
      @Value("${mprp.environment-id}") String env,
      @Value("${mprp.worker:false}") boolean enabled,
      @Value("${mprp.command:serve}") String command) {
    this.db = db;
    this.provider = provider;
    this.callbacks = callbacks;
    this.tx = new TransactionTemplate(manager);
    this.environment = UUID.fromString(env);
    this.enabled = enabled;
    this.command = command;
  }

  public void run(ApplicationArguments args) {
    tx.executeWithoutResult(
        s -> {
          db.exec(
              "insert into system_control(id,environment_id) values(true,?) on conflict do nothing",
              environment);
          if (!environment.equals(
              db.one("select environment_id from system_control where id=true")
                  .get("environment_id")))
            throw new IllegalStateException("Foreign environment database");
        });
  }

  @Scheduled(fixedDelay = 5000)
  public void heartbeat() {
    if (enabled) db.exec("update system_control set worker_at=now() where id=true");
  }

  @Scheduled(fixedDelay = 10000)
  public void pair() {
    if (!enabled) return;
    try {
      var c = db.one("select * from system_control where id=true");
      var r =
          provider.call(
              "/v1/control/handshake/",
              Map.of(
                  "environment_id",
                  environment.toString(),
                  "main_instance_id",
                  c.get("instance_id").toString(),
                  "dispatch_epoch",
                  c.get("dispatch_epoch"),
                  "run_token",
                  c.get("run_id").toString()));
      tx.executeWithoutResult(
          s -> {
            var locked = db.one("select * from system_control where id=true for update");
            Object instance = r.get("simulator_instance_id");
            boolean providerWorkerFresh =
                r.get("worker_heartbeat") instanceof String beat
                    && java.time.Instant.parse(beat)
                        .isAfter(java.time.Instant.now().minusSeconds(30));
            boolean good =
                providerWorkerFresh
                    && environment.toString().equals(r.get("environment_id"))
                    && Boolean.TRUE.equals(r.get("atomic_resolver"))
                    && Boolean.TRUE.equals(r.get("independent_commit"))
                    && Boolean.FALSE.equals(r.get("restore_hold"));
            boolean initial =
                locked.get("paired_at") == null
                    && !Boolean.TRUE.equals(locked.get("restore_requested"))
                    && db.scalar("select count(*) from operation", Long.class) == 0;
            if (instance != null
                && locked.get("provider_instance_id") != null
                && !instance.toString().equals(locked.get("provider_instance_id").toString()))
              good = false;
            db.exec(
                "update system_control set"
                    + " provider_instance_id=coalesce(provider_instance_id,?),provider_checked_at=now(),restore_hold=case"
                    + " when ? then false when ? then restore_hold else true end,paired_at=case"
                    + " when ? then coalesce(paired_at,clock_timestamp()) else paired_at end where"
                    + " id=true",
                instance == null ? null : UUID.fromString(instance.toString()),
                initial && good,
                good,
                good);
          });
    } catch (Exception ignore) {
    }
  }

  @SuppressWarnings("unchecked")
  @Scheduled(fixedDelay = 2000)
  public void recoverFeed() {
    if (!enabled) return;
    try {
      var c = db.one("select * from system_control where id=true");
      if (c.get("provider_instance_id") == null) return;
      long cursor = Db.number(c, "provider_cursor");
      var feed = provider.get("/v1/events/?after=" + cursor);
      db.exec(
          "update system_control set provider_feed_blocked=? where id=true",
          Boolean.TRUE.equals(feed.get("blocked")));
      List<Map<String, Object>> list = (List<Map<String, Object>>) feed.get("events");
      for (var event : list) {
        long next = ((Number) event.get("sequence")).longValue();
        if (next != cursor + 1) throw new DomainException(409, "Provider feed gap");
        tx.executeWithoutResult(
            s -> {
              callbacks.admit(event);
              db.exec("update system_control set provider_cursor=? where id=true", next);
            });
        cursor = next;
      }
      provider.call(
          "/v1/events/ack/",
          Map.of(
              "schema_version",
              1,
              "policy_version",
              "mprp_v1",
              "environment_id",
              c.get("environment_id").toString(),
              "main_instance_id",
              c.get("instance_id").toString(),
              "dispatch_epoch",
              c.get("dispatch_epoch"),
              "cursor",
              cursor));
    } catch (Exception ignore) {
    }
  }

  @Scheduled(fixedDelay = 2000)
  public void recoverInbox() {
    if (!enabled) return;
    for (var row :
        db.query(
            "select id from callback_inbox where state='ACCEPTED' order by created_at limit 50"))
      try {
        callbacks.apply(Db.uuid(row, "id"));
      } catch (Exception blocked) {
        /* Retained for diagnosis/recovery; never acknowledges away an economic effect. */
      }
  }

  @Scheduled(fixedDelay = 60000)
  public void cleanup() {
    if (!enabled) return;
    tx.executeWithoutResult(
        s -> {
          db.exec(
              "update payment p set status='EXPIRED',version=version+1 where status='AUTHORIZED'"
                  + " and authorization_expires_at<=now() and not exists(select 1 from operation o"
                  + " where o.resource_id=p.id and o.kind in('capture','void') and o.status"
                  + " in('QUEUED','DISPATCHING','UNKNOWN'))");
          db.exec("delete from callback_nonce where expires_at<now()");
          db.exec(
              "update report_export set content=null,status='EXPIRED' where expires_at<=now() and"
                  + " status<>'EXPIRED'");
          db.exec(
              "update statement_import set"
                  + " content_cipher=null,parsed_cipher=null,status='EXPIRED',version=version+1"
                  + " where expires_at<=now() and status in('VALIDATED','INVALID')");
        });
  }
}
