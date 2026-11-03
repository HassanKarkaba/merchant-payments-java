package com.hassankarkaba.mprp.operations;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PurgeService {
  private final Db db;
  private final Authz auth;
  private final ProviderClient provider;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final String purgeSecret;
  private final com.hassankarkaba.mprp.merchants.MerchantService merchants;

  public PurgeService(
      Db db,
      Authz auth,
      ProviderClient provider,
      AuditService audit,
      com.hassankarkaba.mprp.merchants.MerchantService merchants,
      org.springframework.transaction.PlatformTransactionManager manager,
      @org.springframework.beans.factory.annotation.Value("${MPRP_PURGE_SECRET:}")
          String purgeSecret) {
    this.db = db;
    this.auth = auth;
    this.provider = provider;
    this.audit = audit;
    this.merchants = merchants;
    this.tx = new TransactionTemplate(manager);
    this.purgeSecret = purgeSecret;
  }

  private Map<String, Object> local(UUID merchant) {
    var m =
        db.one(
            "select id,status,closed_at,ledger_sequence,funds_version from merchant where id=?",
            merchant);
    require(
        m.get("status").equals("CLOSED")
            && m.get("closed_at") instanceof Timestamp
            && !((Timestamp) m.get("closed_at"))
                .toInstant()
                .isAfter(Instant.now().minusSeconds(365L * 86400)),
        409,
        "Purge requires a merchant closed for at least 365 days");
    require(
        db.scalar(
                "select count(*) from operation where merchant_id=? and status"
                    + " in('QUEUED','DISPATCHING','UNKNOWN')",
                Long.class,
                merchant)
            == 0,
        409,
        "Accepted monetary work remains");
    require(
        db.scalar(
                "select count(*) from durable_job where merchant_id=? and state not"
                    + " in('DONE','CANCELLED')",
                Long.class,
                merchant)
            == 0,
        409,
        "Accepted background work remains");
    merchants.assertClosureReady(merchant);
    return Map.of(
        "merchant_id",
        merchant.toString(),
        "closed_at",
        ((Timestamp) m.get("closed_at")).toInstant().toString(),
        "ledger_sequence",
        m.get("ledger_sequence"),
        "funds_version",
        m.get("funds_version"),
        "source_count",
        db.scalar(
            "select count(*) from economic_source where merchant_id=?", Long.class, merchant));
  }

  public UUID propose(UUID merchant, String backups) {
    UUID maker = auth.platform("OPERATIONS");
    auth.stepUp();
    require(
        backups != null && !backups.isBlank() && backups.length() <= 2000,
        400,
        "Explicit backup-store inventory is required");
    var providerState = provider.get("/v1/inventory/summary/?provider_account_id=" + merchant);
    var state = local(merchant);
    var inventory = Map.of("local", state, "provider", providerState, "backup_inventory", backups);
    String hash = CanonicalJson.sha256(inventory);
    return tx.execute(
        s -> {
          auth.platform("OPERATIONS");
          auth.stepUp();
          db.lockMerchant(merchant);
          require(local(merchant).equals(state), 409, "Purge inventory changed");
          UUID id = UUID.randomUUID();
          db.exec(
              "insert into"
                  + " purge_receipt(id,merchant_id,maker_id,inventory,fingerprint,backup_inventory,provider_hash)"
                  + " values(?,?,?,?,?,?,?)",
              id,
              merchant,
              maker,
              CanonicalJson.string(inventory),
              hash,
              backups,
              providerState.get("inventory_hash"));
          audit.record(merchant, maker, "purge.proposed", id, "365-day retention inventory");
          return id;
        });
  }

  public void approve(UUID id) {
    UUID checker = auth.platform("FINANCE_APPROVER");
    var proposal = db.one("select * from purge_receipt where id=?", id);
    if (!"PROPOSED".equals(proposal.get("phase"))) {
      tx.executeWithoutResult(
          s -> {
            com.hassankarkaba.mprp.settlements.WorkflowSupport.lockActors(
                db, checker, Db.uuid(proposal, "maker_id"));
            auth.platform("FINANCE_APPROVER");
            db.lockMerchant(Db.uuid(proposal, "merchant_id"));
            var p = db.one("select * from purge_receipt where id=? for update", id);
            require(
                !"PROPOSED".equals(p.get("phase")) && checker.equals(p.get("checker_id")),
                403,
                "Only the original current checker may replay this approval");
          });
      return;
    }
    auth.stepUp();
    var providerState =
        provider.get("/v1/inventory/summary/?provider_account_id=" + proposal.get("merchant_id"));
    tx.executeWithoutResult(
        s -> {
          com.hassankarkaba.mprp.settlements.WorkflowSupport.lockActors(
              db, checker, Db.uuid(proposal, "maker_id"));
          auth.platform("FINANCE_APPROVER");
          db.lockMerchant(Db.uuid(proposal, "merchant_id"));
          var p = db.one("select * from purge_receipt where id=? for update", id);
          if (!"PROPOSED".equals(p.get("phase"))) {
            require(
                checker.equals(p.get("checker_id")),
                403,
                "Only the original current checker may replay this approval");
            return;
          }
          auth.stepUp();
          require(!checker.equals(p.get("maker_id")), 403, "Independent finance approval required");
          require(
              db.scalar(
                      "select count(*) from app_user where id=? and active and verified and"
                          + " totp_confirmed and platform_role='OPERATIONS'",
                      Long.class,
                      p.get("maker_id"))
                  == 1,
              403,
              "Purge maker authority revoked");
          UUID merchant = Db.uuid(p, "merchant_id");
          db.lockMerchant(merchant);
          require(
              CanonicalJson.sha256(
                      Map.of(
                          "local",
                          local(merchant),
                          "provider",
                          providerState,
                          "backup_inventory",
                          p.get("backup_inventory")))
                  .equals(p.get("fingerprint")),
              409,
              "Purge inventory changed");
          db.exec(
              "update purge_receipt set phase='APPROVED',checker_id=?,approved_at=now() where id=?",
              checker,
              id);
          audit.record(merchant, checker, "purge.approved", id, "Operator-only execution required");
        });
  }

  public void retire(UUID id) {
    require(
        "mprp_purge".equals(db.scalar("select current_user", String.class)),
        403,
        "Operator-only purge database role required");
    var p = db.one("select * from purge_receipt where id=?", id);
    if (Set.of("RETIRED", "PROVIDER_PURGED", "COMPLETED").contains(p.get("phase"))) {
      require(
          db.scalar(
                  "select count(*) from retired_merchant where merchant_id=? and purge_id=?",
                  Long.class,
                  p.get("merchant_id"),
                  id)
              == 1,
          409,
          "Retirement receipt is incomplete");
      return;
    }
    require(
        Set.of("APPROVED", "RETIRED", "PROVIDER_PURGED").contains(p.get("phase")),
        409,
        "Purge not approved");
    UUID merchant = Db.uuid(p, "merchant_id");
    var c = db.one("select * from system_control where id=true");
    var state = local(merchant);
    require(
        state.equals(CanonicalJson.parse(p.get("inventory").toString()).get("local")),
        409,
        "Local purge inventory changed");
    var current = provider.get("/v1/inventory/summary/?provider_account_id=" + merchant);
    require(
        p.get("provider_hash").equals(current.get("inventory_hash")),
        409,
        "Provider inventory changed");
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
            c.get("provider_cursor")));
    provider.callOperator(
        "/v1/accounts/" + merchant + "/retire/",
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
            "provider_account_id",
            merchant.toString(),
            "merchant_closed_at",
            ((Map<?, ?>) CanonicalJson.parse(p.get("inventory").toString()).get("local"))
                .get("closed_at")
                .toString()
                .replace(' ', 'T'),
            "approval_id",
            id.toString(),
            "inventory_hash",
            p.get("provider_hash")),
        purgeSecret);
    tx.executeWithoutResult(
        s -> {
          db.lockMerchant(merchant);
          require(
              local(merchant).equals(state),
              409,
              "Local purge inventory changed during retirement");
          db.exec(
              "insert into retired_merchant(merchant_id,purge_id) values(?,?) on conflict do"
                  + " nothing",
              merchant,
              id);
          db.exec("update purge_receipt set phase='RETIRED' where id=?", id);
        });
  }

  public void execute(UUID id) {
    require(
        "mprp_purge".equals(db.scalar("select current_user", String.class)),
        403,
        "Operator-only purge role required");
    var p = db.one("select * from purge_receipt where id=?", id);
    if (p.get("phase").equals("COMPLETED")) return;
    require(
        Set.of("RETIRED", "PROVIDER_PURGED").contains(p.get("phase")),
        409,
        "Permanent retirement must precede purge execution");
    UUID merchant = Db.uuid(p, "merchant_id");
    var retirement = provider.get("/v1/accounts/" + merchant + "/retirement/");
    require(
        merchant.toString().equals(Objects.toString(retirement.get("account_id"), ""))
            && (Boolean.TRUE.equals(retirement.get("completed"))
                || retirement.get("completed_at") != null),
        409,
        "Provider purge must complete before local purge");
    tx.executeWithoutResult(
        s -> {
          db.lockMerchant(merchant);
          local(merchant);
          require(
              db.scalar(
                      "select count(*) from retired_merchant where merchant_id=? and purge_id=?",
                      Long.class,
                      merchant,
                      id)
                  == 1,
              409,
              "Permanent retirement is required");
          db.scalar(
              "select set_config('mprp.purge_merchant',?,true)", String.class, merchant.toString());
          db.exec(
              "insert into"
                  + " retired_operation_identity(provider_key,merchant_id,operation_id,fingerprint)"
                  + " select provider_key,merchant_id,id,fingerprint from operation where"
                  + " merchant_id=? on conflict do nothing",
              merchant);
          for (String sql :
              List.of(
                  "delete from ledger_entry where merchant_id=?",
                  "delete from journal where merchant_id=?",
                  "delete from allocation where group_id in(select id from match_group where"
                      + " merchant_id=?)",
                  "delete from match_group where merchant_id=?",
                  "delete from statement_import_row where import_id in(select id from"
                      + " statement_import where merchant_id=?)",
                  "delete from statement_import where merchant_id=?",
                  "delete from exception_history where merchant_id=?",
                  "delete from reconciliation_exception where merchant_id=?",
                  "delete from statement_line where merchant_id=?",
                  "delete from correction_proposal_history where merchant_id=?",
                  "delete from correction_proposal where merchant_id=?",
                  "delete from period_report where merchant_id=?",
                  "delete from period_proposal where merchant_id=?",
                  "delete from bank_transfer where merchant_id=?",
                  "delete from settlement_batch where merchant_id=?",
                  "delete from dispute where merchant_id=?",
                  "delete from refund_reservation where merchant_id=?",
                  "delete from checkout_session where link_id in(select id from payment_link where"
                      + " merchant_id=?)",
                  "delete from payment where merchant_id=?",
                  "delete from payment_link where merchant_id=?",
                  "delete from durable_job where merchant_id=?",
                  "delete from matching_run where merchant_id=?",
                  "delete from provider_history_source where merchant_id=?",
                  "delete from provider_history_task where merchant_id=?",
                  "delete from operation where merchant_id=?",
                  "delete from payout_proposal_version where merchant_id=?",
                  "delete from payout where merchant_id=?",
                  "delete from beneficiary where merchant_id=?",
                  "delete from economic_source where merchant_id=?",
                  "delete from accounting_period where merchant_id=?",
                  "delete from report_export where merchant_id=?",
                  "delete from notification where merchant_id=?",
                  "delete from callback_inbox where"
                      + " payload::jsonb->>'provider_account_id'=?::text")) db.exec(sql, merchant);
          db.exec(
              "update merchant set name='Retired fictional merchant',version=version+1 where id=?",
              merchant);
          db.exec("update purge_receipt set phase='COMPLETED',completed_at=now() where id=?", id);
        });
  }
}
