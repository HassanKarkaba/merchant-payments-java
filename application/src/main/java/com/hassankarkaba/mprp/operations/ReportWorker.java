package com.hassankarkaba.mprp.operations;

import com.hassankarkaba.mprp.accounts.SecretCodec;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ReportWorker {
  private final Db db;
  private final TransactionTemplate tx, snapshot;
  private final ReportService reports;
  private final SecretCodec secrets;
  private final boolean enabled;
  private final UUID owner = UUID.randomUUID();
  private final java.util.concurrent.ScheduledExecutorService renewals =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "export-lease-renewal");
            t.setDaemon(true);
            return t;
          });

  @jakarta.annotation.PreDestroy
  public void shutdown() {
    renewals.shutdownNow();
  }

  public ReportWorker(
      Db db,
      org.springframework.transaction.PlatformTransactionManager manager,
      ReportService reports,
      SecretCodec secrets,
      @Value("${mprp.worker:false}") boolean enabled) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.snapshot = new TransactionTemplate(manager);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshot.setReadOnly(true);
    snapshot.setTimeout(120);
    this.reports = reports;
    this.secrets = secrets;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 2000)
  public void discover() {
    if (!enabled) return;
    for (var r :
        db.query(
            "select id from durable_job where kind='EXPORT' and ((state='READY' and due_at<=now())"
                + " or(state='RUNNING' and lease_until<clock_timestamp())) order by due_at"
                + " limit 5")) {
      Map<String, Object> job =
          tx.execute(
              s -> {
                var j =
                    db.maybe(
                        "select * from durable_job where id=? and (state='READY' or"
                            + " lease_until<clock_timestamp()) for update skip locked",
                        r.get("id"));
                if (j.isEmpty()) return null;
                var x = j.get();
                db.exec(
                    "update durable_job set"
                        + " state='RUNNING',owner=?,generation=generation+1,lease_until=clock_timestamp()+interval"
                        + " '30 seconds',run_id=(select run_id from system_control where"
                        + " id=true),dispatch_epoch=(select dispatch_epoch from system_control"
                        + " where id=true) where id=?",
                    owner,
                    r.get("id"));
                x.put("generation", Db.number(x, "generation") + 1);
                return x;
              });
      if (job == null) continue;
      var renewal =
          renewals.scheduleAtFixedRate(
              () -> {
                try {
                  tx.executeWithoutResult(
                      s ->
                          db.exec(
                              "update durable_job j set lease_until=clock_timestamp()+interval '30"
                                  + " seconds' from system_control c where c.id=true and j.id=? and"
                                  + " j.owner=? and j.generation=? and j.state='RUNNING' and"
                                  + " j.lease_until>clock_timestamp() and j.run_id=c.run_id and"
                                  + " j.dispatch_epoch=c.dispatch_epoch",
                              job.get("id"),
                              owner,
                              job.get("generation")));
                } catch (Exception ignored) {
                }
              },
              10,
              10,
              java.util.concurrent.TimeUnit.SECONDS);
      try {
        String data = snapshot.execute(s -> build((UUID) job.get("resource_id")));
        tx.executeWithoutResult(
            s -> {
              var preliminary =
                  db.one(
                      "select user_id,merchant_id from report_export where id=?",
                      job.get("resource_id"));
              db.one("select id from app_user where id=? for update", preliminary.get("user_id"));
              if (preliminary.get("merchant_id") != null)
                db.lockMerchant((UUID) preliminary.get("merchant_id"));
              db.one("select * from system_control where id=true for update");
              var e =
                  db.one(
                      "select * from report_export where id=? for update", job.get("resource_id"));
              if (!reports.authority(e)) throw new DomainException(403, "Export authority revoked");
              if (db.scalar(
                      "select count(*) from durable_job j join system_control c on c.id=true where"
                          + " j.id=? and j.owner=? and j.generation=? and"
                          + " j.lease_until>clock_timestamp() and j.state='RUNNING' and"
                          + " j.run_id=c.run_id and j.dispatch_epoch=c.dispatch_epoch",
                      Long.class,
                      job.get("id"),
                      owner,
                      job.get("generation"))
                  != 1) return;
              byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
              if (bytes.length > 20 * 1024 * 1024)
                throw new DomainException(413, "Export byte limit exceeded");
              db.exec(
                  "update report_export set status='READY',content=?,digest=? where id=? and"
                      + " expires_at>now()",
                  secrets
                      .encrypt(data, "export:" + job.get("resource_id"))
                      .getBytes(StandardCharsets.UTF_8),
                  CanonicalJson.digest(bytes),
                  job.get("resource_id"));
              db.exec(
                  "update durable_job set state='DONE',lease_until=null where id=?", job.get("id"));
              db.exec("update system_control set unfinished_jobs=unfinished_jobs-1 where id=true");
            });
      } catch (Exception failed) {
        tx.executeWithoutResult(
            s -> {
              db.one("select * from system_control where id=true for update");
              if (db.scalar(
                      "select count(*) from durable_job j join system_control c on c.id=true where"
                          + " j.id=? and j.owner=? and j.generation=? and"
                          + " j.lease_until>clock_timestamp() and j.state='RUNNING' and"
                          + " j.run_id=c.run_id and j.dispatch_epoch=c.dispatch_epoch",
                      Long.class,
                      job.get("id"),
                      owner,
                      job.get("generation"))
                  != 1) return;
              db.exec("update system_control set unfinished_jobs=unfinished_jobs-1 where id=true");
              db.exec(
                  "update report_export set status='FAILED',content=null where id=?",
                  job.get("resource_id"));
              db.exec(
                  "update durable_job set state='DONE',last_error='Export unavailable' where id=?"
                      + " and owner=? and generation=?",
                  job.get("id"),
                  owner,
                  job.get("generation"));
            });
      } finally {
        renewal.cancel(false);
      }
    }
  }

  private String build(UUID id) {
    var e = db.one("select * from report_export where id=?", id);
    if (!reports.authority(e)) throw new DomainException(403, "Export authority revoked");
    if (!((java.sql.Timestamp) e.get("expires_at")).toInstant().isAfter(java.time.Instant.now()))
      throw new DomainException(410, "Export expired");
    List<Map<String, Object>> rows =
        switch (e.get("kind").toString()) {
          case "payments" ->
              db.query(
                  "select id,status,captured_minor,refunded_minor,created_at from payment where"
                      + " merchant_id=? and created_at<=? order by created_at,id limit 100001",
                  e.get("merchant_id"),
                  e.get("cutoff"));
          case "ledger" ->
              db.query(
                  "select"
                      + " j.sequence,j.period_month,s.provider_source_id,s.event_type,s.amount_minor"
                      + " from journal j join economic_source s on s.id=j.source_id where"
                      + " j.merchant_id=? and j.created_at<=? order by j.sequence limit 100001",
                  e.get("merchant_id"),
                  e.get("cutoff"));
          default ->
              db.query(
                  "select id,email,verified,created_at from app_user where id=?", e.get("user_id"));
        };
    if (rows.size() > 100000) throw new DomainException(413, "Export row limit exceeded");
    StringBuilder csv = new StringBuilder();
    if (!rows.isEmpty()) {
      var keys = rows.getFirst().keySet();
      csv.append(String.join(",", keys)).append("\r\n");
      for (var row : rows) {
        csv.append(
                keys.stream()
                    .map(
                        k ->
                            (row.get(k) instanceof Number number
                                ? new java.math.BigDecimal(number.toString())
                                    .toBigIntegerExact()
                                    .toString()
                                : ReportService.csv(Objects.toString(row.get(k), ""))))
                    .collect(java.util.stream.Collectors.joining(",")))
            .append("\r\n");
        if (csv.length() > 20 * 1024 * 1024)
          throw new DomainException(413, "Export byte limit exceeded");
      }
    }
    return csv.toString();
  }
}
