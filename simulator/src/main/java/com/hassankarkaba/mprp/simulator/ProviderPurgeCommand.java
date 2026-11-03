package com.hassankarkaba.mprp.simulator;

import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Operator-only CLI; database role and a separately provisioned secret are both mandatory. */
@Component
public class ProviderPurgeCommand implements ApplicationRunner {
  private final ProviderStore store;
  private final Environment env;
  private final TransactionTemplate tx;

  public ProviderPurgeCommand(
      ProviderStore store, Environment env, PlatformTransactionManager manager) {
    this.store = store;
    this.env = env;
    this.tx = new TransactionTemplate(manager);
  }

  public void run(ApplicationArguments args) {
    String command = env.getProperty("mprp.command", "serve");
    if (command.equals("serve")) {
      var roleCheck =
          store.one(
              "SELECT current_user AS role,rolsuper FROM pg_roles WHERE rolname=current_user");
      if (!"simulator_runtime".equals(roleCheck.get("role"))
          || Boolean.TRUE.equals(roleCheck.get("rolsuper"))
          || Boolean.TRUE.equals(
              store
                  .one(
                      "SELECT has_database_privilege(current_user,current_database(),'TEMP') AS"
                          + " unsafe")
                  .get("unsafe"))
          || Boolean.TRUE.equals(
              store
                  .one("SELECT has_schema_privilege(current_user,'public','CREATE') AS unsafe")
                  .get("unsafe")))
        throw new IllegalStateException("Simulator server requires a safe runtime role");
      return;
    }
    if (command.equals("migrate")) return;
    if (!Set.of("configure-purge", "purge").contains(command))
      throw new IllegalArgumentException("Unsupported simulator command");
    String secret = env.getProperty("mprp.purge-secret", "");
    if (secret.length() < 32)
      throw new IllegalArgumentException("Separate purge credential is required");
    String digest = CanonicalJson.digest(secret.getBytes(StandardCharsets.UTF_8));
    String role = store.one("SELECT current_user AS role").get("role").toString();
    if (command.equals("configure-purge")) {
      if (!role.equals("simulator_owner"))
        throw new IllegalStateException("Only migration owner can provision purge authority");
      tx.executeWithoutResult(
          s -> {
            var c = store.one("SELECT purge_secret_digest FROM sim_control WHERE id=1 FOR UPDATE");
            Object old = c.get("purge_secret_digest");
            if (old != null && !old.equals(digest))
              throw new IllegalStateException("Refusing to overwrite purge authority");
            store.exec("UPDATE sim_control SET purge_secret_digest=? WHERE id=1", digest);
          });
      return;
    }
    if (!role.equals("simulator_purge"))
      throw new IllegalStateException("Purge requires the operator-only simulator_purge role");
    Object expected =
        store
            .one("SELECT purge_secret_digest FROM sim_control WHERE id=1")
            .get("purge_secret_digest");
    if (expected == null
        || !java.security.MessageDigest.isEqual(
            digest.getBytes(StandardCharsets.US_ASCII),
            expected.toString().getBytes(StandardCharsets.US_ASCII)))
      throw new IllegalStateException("Purge authority verification failed");
    UUID account = UUID.fromString(env.getRequiredProperty("mprp.purge-account")),
        approval = UUID.fromString(env.getRequiredProperty("mprp.purge-approval"));
    String hash = env.getRequiredProperty("mprp.purge-inventory-hash");
    tx.executeWithoutResult(
        s -> {
          var r =
              store.one(
                  "SELECT * FROM sim_retired_namespaces WHERE account_id=? FOR UPDATE", account);
          if (!approval.equals(r.get("approval_id")) || !hash.equals(r.get("inventory_hash")))
            throw new IllegalStateException("Purge approval and retired inventory differ");
          if (store.count(
                  "SELECT count(*) FROM sim_retired_namespaces WHERE account_id=? AND"
                      + " merchant_closed_at<=now()-interval '365 days' AND phase>=1",
                  account)
              != 1) throw new IllegalStateException("Namespace is not eligible for purge");
        });
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(120).toNanos();
    for (String table :
        List.of(
            "sim_event_failure",
            "sim_events",
            "sim_manifests",
            "sim_transfers",
            "sim_disputes",
            "sim_payments",
            "sim_sources",
            "sim_batches")) {
      while (true) {
        if (System.nanoTime() > deadline)
          throw new IllegalStateException("Purge checkpoint retained; resume the same approval");
        int deleted =
            tx.execute(
                s -> {
                  store.one(
                      "SELECT * FROM sim_retired_namespaces WHERE account_id=? FOR UPDATE",
                      account);
                  return store.exec(
                      "DELETE FROM "
                          + table
                          + " WHERE ctid IN(SELECT ctid FROM "
                          + table
                          + " WHERE account_id=? LIMIT 1000)",
                      account);
                });
        if (deleted == 0) break;
      }
    }
    tx.executeWithoutResult(
        s ->
            store.exec(
                "UPDATE sim_retired_namespaces SET phase=GREATEST(phase,2) WHERE account_id=?",
                account));
    while (true) {
      if (System.nanoTime() > deadline)
        throw new IllegalStateException("Purge checkpoint retained; resume the same approval");
      int redacted =
          tx.execute(
              s -> {
                store.one(
                    "SELECT * FROM sim_retired_namespaces WHERE account_id=? FOR UPDATE", account);
                var operations =
                    store.rows(
                        "SELECT * FROM sim_operations WHERE account_id=? AND"
                            + " result->>'business_state'<>'PURGED' ORDER BY sequence LIMIT 100 FOR"
                            + " UPDATE",
                        account);
                for (var op : operations) {
                  Map<String, Object> result =
                      ProviderStore.map(
                          "operation_key",
                          op.get("operation_key"),
                          "payload_hash",
                          op.get("payload_hash"),
                          "provider_account_id",
                          account,
                          "resource_id",
                          op.get("resource_id"),
                          "state",
                          op.get("state"),
                          "business_state",
                          "PURGED",
                          "retired",
                          true,
                          "currency",
                          "USD",
                          "sources",
                          List.of());
                  store.exec(
                      "UPDATE sim_operations SET payload='{}'::jsonb,result=?::jsonb WHERE"
                          + " operation_key=?",
                      CanonicalJson.string(result),
                      op.get("operation_key"));
                }
                return operations.size();
              });
      if (redacted == 0) break;
    }
    tx.executeWithoutResult(
        s ->
            store.exec(
                "UPDATE sim_retired_namespaces SET"
                    + " phase=3,completed_at=coalesce(completed_at,now()) WHERE account_id=?",
                account));
    System.out.println(CanonicalJson.string(store.retirement(account)));
  }
}
