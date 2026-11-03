package com.hassankarkaba.mprp.operations;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RecoveryService {
  private final Db db;
  private final Authz auth;
  private final ProviderClient provider;
  private final AuditService audit;
  private final TransactionTemplate tx;

  public RecoveryService(
      Db db,
      Authz auth,
      ProviderClient provider,
      AuditService audit,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.db = db;
    this.auth = auth;
    this.provider = provider;
    this.audit = audit;
    this.tx = new TransactionTemplate(manager);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> inventory() {
    var summary = provider.get("/v1/inventory/summary/");
    var blockers = new ArrayList<String>();
    Set<String> providerKeys = new HashSet<>(), providerSources = new HashSet<>();
    java.time.Instant taskDeadline = java.time.Instant.now().plusSeconds(120);
    long after = 0;
    Object snapshot = null;
    int pages = 0;
    do {
      String path =
          "/v1/inventory/?after=" + after + (snapshot == null ? "" : "&snapshot=" + snapshot);
      require(
          java.time.Instant.now().isBefore(taskDeadline),
          503,
          "Inventory deadline reached; keep recovery hold and retry bounded inspection");
      var page = provider.get(path);
      if (snapshot == null) snapshot = page.get("snapshot");
      for (var r : (List<Map<String, Object>>) page.get("operations")) {
        String key = r.get("operation_key").toString();
        providerKeys.add(key);
        var local = db.maybe("select id,status from operation where provider_key=?", key);
        if (local.isEmpty()
            && db.scalar(
                    "select count(*) from retired_operation_identity where provider_key=? and"
                        + " fingerprint=?",
                    Long.class,
                    key,
                    r.get("payload_hash"))
                == 0) addBlocker(blockers, "Provider commitment lacks retained admission: " + key);
        else if (local.isPresent()
            && !Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(local.get().get("status")))
          addBlocker(blockers, "Operation requires resolution: " + local.get().get("id"));
      }
      long next = ((Number) page.get("next_after")).longValue();
      if (Boolean.TRUE.equals(page.get("complete"))) break;
      require(next > after && ++pages <= 1000, 409, "Inventory pagination incomplete");
      after = next;
    } while (true);
    after = 0;
    snapshot = null;
    pages = 0;
    do {
      var page =
          provider.get(
              "/v1/inventory/sources/?after="
                  + after
                  + (snapshot == null ? "" : "&snapshot=" + snapshot));
      if (snapshot == null) snapshot = page.get("snapshot");
      require(
          java.time.Instant.now().isBefore(taskDeadline),
          503,
          "Inventory deadline reached; keep recovery hold and retry bounded inspection");
      for (var s : (List<Map<String, Object>>) page.get("sources")) {
        providerSources.add(
            s.get("provider_account_id") + ":" + s.get("source_id") + ":" + s.get("event_type"));
        var local =
            db.maybe(
                "select s.id,s.amount_minor,s.recognized,j.id as journal_id from economic_source s"
                    + " left join journal j on j.source_id=s.id where s.merchant_id=? and"
                    + " s.provider_source_id=? and s.event_type=?",
                UUID.fromString(s.get("provider_account_id").toString()),
                s.get("source_id"),
                s.get("event_type"));
        if (local.isEmpty())
          addBlocker(blockers, "Provider source missing locally: " + s.get("source_id"));
        else if (Db.number(local.get(), "amount_minor")
                != ((Number) s.get("amount_minor")).longValue()
            || (Boolean.TRUE.equals(local.get().get("recognized"))
                && !s.get("event_type").equals("BATCH_ZERO")
                && local.get().get("journal_id") == null))
          addBlocker(blockers, "Source accounting mismatch: " + s.get("source_id"));
      }
      long next = ((Number) page.get("next_after")).longValue();
      if (Boolean.TRUE.equals(page.get("complete"))) break;
      require(next > after && ++pages <= 1000, 409, "Source inventory pagination incomplete");
      after = next;
    } while (true);
    for (var local : db.query("select provider_key from operation where status='SUCCEEDED'"))
      if (!providerKeys.contains(local.get("provider_key").toString()))
        addBlocker(
            blockers,
            "Local completed operation is missing from provider inventory: "
                + local.get("provider_key"));
    for (var local :
        db.query("select merchant_id,provider_source_id,event_type from economic_source"))
      if (!providerSources.contains(
          local.get("merchant_id")
              + ":"
              + local.get("provider_source_id")
              + ":"
              + local.get("event_type")))
        addBlocker(
            blockers,
            "Local economic source is missing from provider inventory: "
                + local.get("provider_source_id"));
    if (db.scalar("select count(*) from callback_inbox where state='ACCEPTED'", Long.class) > 0)
      addBlocker(blockers, "Accepted provider callbacks remain");
    if (db.scalar(
            "select count(*) from durable_job where kind='PROVIDER_HISTORY' and state not"
                + " in('DONE','CANCELLED')",
            Long.class)
        > 0) addBlocker(blockers, "Accepted provider history remains");
    if (db.scalar(
            "select count(*) from operation where status in('QUEUED','DISPATCHING','UNKNOWN')",
            Long.class)
        > 0) addBlocker(blockers, "Nonterminal monetary operations remain");
    var c = db.one("select * from system_control where id=true");
    if (((Number) summary.get("event_high_water")).longValue() != Db.number(c, "provider_cursor"))
      addBlocker(blockers, "Provider event feed is not fully inventoried");
    return Map.of("provider", summary, "blockers", blockers, "local", localFingerprint());
  }

  private void addBlocker(List<String> blockers, String message) {
    if (blockers.size() < 2000) blockers.add(message);
    else if (blockers.size() == 2000)
      blockers.add("Additional findings omitted from bounded display; recovery remains held");
  }

  private String localFingerprint() {
    var state = new TreeMap<String, Object>();
    state.put(
        "control",
        safe(
            db.query(
                "select"
                    + " environment_id,instance_id,run_id,dispatch_epoch,provider_cursor,provider_instance_id"
                    + " from system_control where id=true")));
    state.put(
        "operations",
        safe(
            db.query(
                "select id,provider_key,fingerprint,status,version from operation order by id")));
    state.put(
        "sources",
        safe(
            db.query(
                "select id,merchant_id,provider_source_id,event_type,amount_minor,recognized from"
                    + " economic_source order by id")));
    state.put("journals", safe(db.query("select id,source_id,sequence from journal order by id")));
    state.put(
        "refunds",
        safe(
            db.query(
                "select id,operation_id,amount_minor,state from refund_reservation order by id")));
    state.put(
        "payouts", safe(db.query("select id,status,reserved,version from payout order by id")));
    return CanonicalJson.sha256(state);
  }

  private List<Map<String, Object>> safe(List<Map<String, Object>> rows) {
    return rows.stream()
        .map(
            r -> {
              var m = new TreeMap<String, Object>();
              r.forEach(
                  (k, v) ->
                      m.put(
                          k,
                          v == null
                              ? null
                              : v instanceof Number || v instanceof Boolean ? v : v.toString()));
              return (Map<String, Object>) m;
            })
        .toList();
  }

  public UUID propose() {
    UUID maker = auth.platform("OPERATIONS");
    auth.stepUp();
    var snapshot = inventory();
    String hash = CanonicalJson.sha256(snapshot);
    return tx.execute(
        s -> {
          auth.platform("OPERATIONS");
          auth.stepUp();
          require(localFingerprint().equals(snapshot.get("local")), 409, "Local inventory changed");
          UUID id = UUID.randomUUID();
          db.exec(
              "insert into recovery_inventory(id,maker_id,fingerprint,content) values(?,?,?,?)",
              id,
              maker,
              hash,
              CanonicalJson.string(snapshot));
          audit.record(
              null, maker, "recovery.inventory.proposed", id, "Immutable paired-state inventory");
          return id;
        });
  }

  @SuppressWarnings("unchecked")
  public void release(UUID id) {
    UUID checker = auth.platform("FINANCE_APPROVER");
    var proposal = db.one("select * from recovery_inventory where id=?", id);
    if ("RELEASED".equals(proposal.get("status"))) {
      tx.executeWithoutResult(
          s -> {
            com.hassankarkaba.mprp.settlements.WorkflowSupport.lockActors(
                db, checker, Db.uuid(proposal, "maker_id"));
            auth.platform("FINANCE_APPROVER");
            var frozen = db.one("select * from recovery_inventory where id=? for update", id);
            require(
                "RELEASED".equals(frozen.get("status")) && checker.equals(frozen.get("checker_id")),
                403,
                "Only the original current checker may replay this release");
          });
      return;
    }
    require(
        proposal.get("checker_id") == null || checker.equals(proposal.get("checker_id")),
        409,
        "Another checker owns this recovery decision");
    auth.stepUp();
    var current = inventory();
    require(
        !checker.equals(proposal.get("maker_id")),
        403,
        "An independent finance approver is required");
    require(
        CanonicalJson.sha256(current).equals(proposal.get("fingerprint")),
        409,
        "Recovery inventory changed; create a fresh proposal");
    require(
        ((List<?>) current.get("blockers")).isEmpty(), 409, "Inventory has unresolved findings");
    boolean proceed =
        Boolean.TRUE.equals(
            tx.execute(
                s -> {
                  com.hassankarkaba.mprp.settlements.WorkflowSupport.lockActors(
                      db, checker, Db.uuid(proposal, "maker_id"));
                  auth.platform("FINANCE_APPROVER");
                  var frozen = db.one("select * from recovery_inventory where id=? for update", id);
                  if ("RELEASED".equals(frozen.get("status"))) {
                    require(
                        checker.equals(frozen.get("checker_id")),
                        403,
                        "Only the original current checker may replay this release");
                    return false;
                  }
                  auth.stepUp();
                  require(
                      Set.of("PROPOSED", "RELEASING").contains(frozen.get("status"))
                          && (frozen.get("checker_id") == null
                              || checker.equals(frozen.get("checker_id"))),
                      409,
                      "Recovery decision changed");
                  require(
                      !checker.equals(frozen.get("maker_id"))
                          && db.scalar(
                                  "select count(*) from app_user where id=? and active and verified"
                                      + " and totp_confirmed and platform_role='OPERATIONS'",
                                  Long.class,
                                  frozen.get("maker_id"))
                              == 1,
                      403,
                      "Current independent maker authority required");
                  db.one("select id from system_control where id=true for update");
                  require(
                      CanonicalJson.sha256(current).equals(frozen.get("fingerprint"))
                          && localFingerprint().equals(current.get("local")),
                      409,
                      "Recovery inventory changed before release");
                  db.exec(
                      "update recovery_inventory set status='RELEASING',checker_id=? where id=?",
                      checker,
                      id);
                  return true;
                }));
    if (!proceed) return;
    var c = db.one("select * from system_control where id=true");
    var summary = (Map<String, Object>) current.get("provider");
    var result =
        provider.call(
            "/v1/control/restore-release/",
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
                "acknowledged_cursor",
                c.get("provider_cursor"),
                "inventory_hash",
                summary.get("inventory_hash")));
    tx.executeWithoutResult(
        s -> {
          com.hassankarkaba.mprp.settlements.WorkflowSupport.lockActors(
              db, checker, Db.uuid(proposal, "maker_id"));
          auth.platform("FINANCE_APPROVER");
          var locked = db.one("select * from recovery_inventory where id=? for update", id);
          if ("RELEASED".equals(locked.get("status"))) {
            require(
                checker.equals(locked.get("checker_id")),
                403,
                "Only the original current checker may replay this release");
            return;
          }
          auth.stepUp();
          require(
              "RELEASING".equals(locked.get("status")) && checker.equals(locked.get("checker_id")),
              409,
              "Recovery decision changed during release");
          require(
              !checker.equals(locked.get("maker_id"))
                  && db.scalar(
                          "select count(*) from app_user where id=? and active and verified and"
                              + " totp_confirmed and platform_role='OPERATIONS'",
                          Long.class,
                          locked.get("maker_id"))
                      == 1,
              403,
              "Current independent maker authority required");
          db.one("select id from system_control where id=true for update");
          require(
              localFingerprint().equals(current.get("local")),
              409,
              "Local state changed during release");
          require(Boolean.FALSE.equals(result.get("restore_hold")), 409, "Provider hold remains");
          require(
              Objects.equals(
                      summary.get("simulator_instance_id"), result.get("simulator_instance_id"))
                  && Objects.equals(
                      c.get("environment_id").toString(), result.get("environment_id")),
              409,
              "Provider release identity changed");
          db.exec(
              "update system_control set"
                  + " restore_hold=false,restore_requested=false,paired_at=clock_timestamp(),provider_instance_id=?,clock_at=now(),provider_checked_at=now()"
                  + " where id=true",
              UUID.fromString(result.get("simulator_instance_id").toString()));
          db.exec("update recovery_inventory set status='RELEASED' where id=?", id);
          audit.record(null, checker, "recovery.hold.released", id, "Inventory reconciled");
        });
  }
}
