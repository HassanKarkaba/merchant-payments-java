package com.hassankarkaba.mprp.merchants;

import com.hassankarkaba.mprp.accounts.AccountRules;
import com.hassankarkaba.mprp.accounts.AccountService;
import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.accounts.SecretCodec;
import com.hassankarkaba.mprp.operations.Db;
import com.hassankarkaba.mprp.operations.DomainException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {
  private final Db db;
  private final Authz authz;
  private final AccountService accounts;
  private final String publicUrl;

  public MerchantService(
      Db db,
      Authz authz,
      AccountService accounts,
      @Value("${mprp.public-url:http://localhost:8180}") String publicUrl) {
    this.db = db;
    this.authz = authz;
    this.accounts = accounts;
    this.publicUrl = publicUrl;
  }

  @Transactional
  public void settings(UUID merchant, Map<String, String> form) {
    AccountRules.fields(form.keySet(), "name", "timezone", "expected_version");
    String name = AccountRules.business(form.get("name")),
        zone = AccountRules.timezone(form.get("timezone"));
    long version = version(form);
    UUID actor = authz.require(merchant, "OWNER");
    db.lockMerchant(merchant);
    if (db.exec(
            "update merchant set name=?,timezone=?,version=version+1 where id=? and version=?",
            name,
            zone,
            merchant,
            version)
        != 1) throw new DomainException(409, "Merchant settings changed. Reload the page.");
    accounts.event(actor, merchant, "MERCHANT_SETTINGS_CHANGED");
  }

  @Transactional
  public void invite(UUID merchant, Map<String, String> form) {
    AccountRules.fields(form.keySet(), "email", "role");
    String email = AccountRules.email(form.get("email")), role = role(form.get("role"));
    UUID actor = authz.require(merchant, "OWNER");
    db.lockMerchant(merchant);
    if (db.scalar(
            "select count(*) from membership where merchant_id=? and active", Long.class, merchant)
        >= 100) throw new DomainException(409, "A merchant may have at most 100 active members.");
    if (db.scalar(
            "select count(*) from membership x join app_user u on u.id=x.user_id where"
                + " x.merchant_id=? and u.email=? and x.active",
            Long.class,
            merchant,
            email)
        > 0)
      throw new DomainException(
          409, "This address is already an active member. Use the member change form.");
    String token = SecretCodec.randomToken();
    UUID id = UUID.randomUUID();
    db.exec(
        "insert into"
            + " account_token(id,token_digest,kind,merchant_id,issuer_id,email,role,expires_at)"
            + " values (?,?,'INVITE',?,?,?,?,clock_timestamp()+interval '24 hours')",
        id,
        SecretCodec.digest(token),
        merchant,
        actor,
        email,
        role);
    accounts.mail(
        email,
        "Invitation to a fictional merchant",
        publicUrl + "/accounts/invitations/" + token + "/",
        86400);
    accounts.event(actor, merchant, "MEMBER_INVITED");
  }

  @Transactional
  public void accept(String token) {
    UUID actor = authz.actorForLocking();
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
      authz.current();
      throw new DomainException(400, "Invitation is invalid or expired.");
    }
    var invitation =
        db.maybe(
                "select * from account_token where token_digest=? and kind='INVITE'",
                SecretCodec.digest(token))
            .orElseThrow(() -> new DomainException(400, "Invitation is invalid or expired."));
    UUID issuer = Db.uuid(invitation, "issuer_id"), merchant = Db.uuid(invitation, "merchant_id");
    db.query("select id from app_user where id in (?,?) order by id for update", actor, issuer);
    authz.current();
    db.lockMerchant(merchant);
    var t =
        db.one(
            "select *,expires_at>clock_timestamp() as valid from account_token where id=? for"
                + " update",
            invitation.get("id"));
    if (!Boolean.TRUE.equals(t.get("valid"))
        || t.get("revoked_at") != null
        || t.get("consumed_at") != null)
      throw new DomainException(409, "Invitation is expired, revoked or already accepted.");
    var u = db.one("select * from app_user where id=?", actor);
    if (!t.get("email").equals(u.get("email")))
      throw new DomainException(403, "This invitation is for a different email address.");
    if (db.scalar(
            "select count(*) from app_user u join membership x on x.user_id=u.id where u.id=? and"
                + " u.active and u.verified and u.totp_confirmed and x.merchant_id=? and"
                + " x.role='OWNER' and x.active",
            Long.class,
            issuer,
            merchant)
        != 1) throw new DomainException(403, "The inviting owner no longer has authority.");
    var existing =
        db.maybe(
            "select * from membership where user_id=? and merchant_id=? for update",
            actor,
            merchant);
    if (existing.isPresent())
      throw new DomainException(
          409,
          Boolean.TRUE.equals(existing.get().get("active"))
              ? "You are already a member. Invitations cannot change your role."
              : "Revoked membership cannot be reactivated by an invitation.");
    if (db.scalar(
            "select count(*) from membership where merchant_id=? and active", Long.class, merchant)
        >= 100) throw new DomainException(409, "Merchant member limit reached.");
    if ("CLOSED".equals(db.one("select status from merchant where id=?", merchant).get("status")))
      throw new DomainException(409, "Merchant is closed.");
    db.exec(
        "insert into membership(id,user_id,merchant_id,role) values (?,?,?,?)",
        UUID.randomUUID(),
        actor,
        merchant,
        t.get("role"));
    db.exec("update account_token set consumed_at=clock_timestamp() where id=?", t.get("id"));
    accounts.event(actor, merchant, "INVITATION_ACCEPTED");
  }

  @Transactional
  public void changeMember(UUID merchant, UUID member, Map<String, String> form) {
    AccountRules.fields(form.keySet(), "role", "active", "expected_version");
    String role = role(form.get("role"));
    if (!java.util.Set.of("true", "false").contains(form.getOrDefault("active", "")))
      throw new DomainException(400, "Choose an explicit membership state.");
    boolean active = "true".equals(form.get("active"));
    long version = version(form);
    UUID actor = authz.actorForLocking();
    var target =
        db.one("select user_id from membership where id=? and merchant_id=?", member, merchant);
    db.query(
        "select id from app_user where id in (?,?) order by id for update",
        actor,
        target.get("user_id"));
    authz.require(merchant, "OWNER");
    db.lockMerchant(merchant);
    var old =
        db.one(
            "select * from membership where id=? and merchant_id=? for update", member, merchant);
    if (Db.number(old, "version") != version)
      throw new DomainException(409, "Membership changed. Reload the page.");
    if (!Boolean.TRUE.equals(old.get("active")))
      throw new DomainException(409, "Revocation is permanent.");
    if ("OWNER".equals(old.get("role"))
        && (!active || !"OWNER".equals(role))
        && db.scalar(
                "select count(*) from membership x join app_user u on u.id=x.user_id where"
                    + " x.merchant_id=? and x.active and x.role='OWNER' and u.active",
                Long.class,
                merchant)
            <= 1
        && !"CLOSED"
            .equals(db.one("select status from merchant where id=?", merchant).get("status")))
      throw new DomainException(409, "The last active owner cannot be removed or demoted.");
    db.exec(
        "update membership set role=?,active=?,version=version+1 where id=?", role, active, member);
    accounts.event(actor, merchant, "MEMBERSHIP_CHANGED");
  }

  @Transactional
  public void revokeInvite(UUID merchant, UUID invite) {
    UUID actor = authz.require(merchant, "OWNER");
    db.lockMerchant(merchant);
    db.exec(
        "update account_token set revoked_at=clock_timestamp() where id=? and merchant_id=? and"
            + " kind='INVITE' and consumed_at is null",
        invite,
        merchant);
    accounts.event(actor, merchant, "INVITATION_REVOKED");
  }

  @Transactional
  public void requestClose(UUID merchant) {
    UUID actor = authz.require(merchant, "OWNER");
    authz.stepUp();
    String status = db.scalar("select status from merchant where id=?", String.class, merchant);
    if (!java.util.Set.of("ACTIVE", "SUSPENDED").contains(status))
      throw new DomainException(409, "Only active or suspended merchants may request closure.");
    // Drain economic duties while ordinary financial admission remains available.
    // Matching, exception investigation and month close can continue after this boundary.
    assertFinancialDrain(merchant);
    if (db.exec(
            "update merchant set status='CLOSING',version=version+1 where id=? and status"
                + " in('ACTIVE','SUSPENDED')",
            merchant)
        != 1)
      throw new DomainException(409, "Only active or suspended merchants may request closure.");
    accounts.event(actor, merchant, "MERCHANT_CLOSURE_REQUESTED");
    authz.stepUp();
  }

  @Transactional
  public void lifecycle(UUID merchant, String decision, long expected) {
    UUID actor = authz.platform("OPERATIONS");
    authz.stepUp();
    db.lockMerchant(merchant);
    var m = db.one("select * from merchant where id=?", merchant);
    if (Db.number(m, "version") != expected)
      throw new DomainException(409, "Merchant changed. Reload the page.");
    String old = m.get("status").toString();
    String next;
    switch (decision) {
      case "activate" -> {
        if (!old.equals("PENDING"))
          throw new DomainException(409, "Activation requires pending status.");
        next = "ACTIVE";
      }
      case "suspend" -> {
        if (!old.equals("ACTIVE"))
          throw new DomainException(409, "Suspension requires active status.");
        next = "SUSPENDED";
      }
      case "resume" -> {
        if (!old.equals("SUSPENDED"))
          throw new DomainException(409, "Resumption requires suspended status.");
        next = "ACTIVE";
      }
      case "close" -> {
        if (!old.equals("CLOSING"))
          throw new DomainException(409, "Closure must first be requested by an owner.");
        assertClosure(merchant);
        next = "CLOSED";
      }
      default -> throw new DomainException(400, "Unknown lifecycle action.");
    }
    db.exec("update merchant set status=?,version=version+1 where id=?", next, merchant);
    if (next.equals("CLOSED")) {
      db.exec(
          "update payment_link set revoked_at=clock_timestamp() where merchant_id=? and revoked_at"
              + " is null",
          merchant);
      db.exec(
          "update account_token set revoked_at=clock_timestamp() where merchant_id=? and"
              + " kind='INVITE' and revoked_at is null",
          merchant);
      db.exec(
          "update membership set active=false,version=version+1 where merchant_id=? and"
              + " role<>'OWNER' and active",
          merchant);
    }
    accounts.event(actor, merchant, "MERCHANT_" + next);
    authz.stepUp();
  }

  private void assertClosure(UUID merchant) {
    assertFinancialDrain(merchant);
    String[] blockers = {
      "select count(*) from durable_job where merchant_id=? and state not in('DONE','CANCELLED')",
      "select count(*) from (select account,sum(debit::numeric-credit::numeric) balance from"
          + " ledger_entry where merchant_id=? group by account having"
          + " sum(debit::numeric-credit::numeric)<>0) q",
      "select count(*) from operation where merchant_id=? and status not"
          + " in('SUCCEEDED','FAILED','CANCELLED')",
      "select count(*) from refund_reservation where merchant_id=? and state='ACTIVE'",
      "select count(*) from payout where merchant_id=? and (reserved or status not"
          + " in('SUCCEEDED','FAILED','CANCELLED','REJECTED'))",
      "select count(*) from dispute where merchant_id=? and status not in('WON','LOST')",
      "select count(*) from accounting_period where merchant_id=? and status<>'CLOSED'",
      "select count(*) from reconciliation_exception where merchant_id=? and state not"
          + " in('RESOLVED','ACCEPTED_NONFINANCIAL')",
      "select count(*) from economic_source s where merchant_id=? and (not recognized or"
          + " (amount_minor<>0 and not exists(select 1 from journal j where j.source_id=s.id)))",
      "select count(*) from statement_line l where merchant_id=? and"
          + " (abs(l.amount_minor::numeric)<>(select coalesce(sum(a.amount_minor),0) from"
          + " allocation a join match_group g on g.id=a.group_id where a.line_id=l.id and"
          + " g.status='MATCHED') or (l.amount_minor=0 and not exists(select 1 from allocation a"
          + " join match_group g on g.id=a.group_id where a.line_id=l.id and g.status='MATCHED')))"
    };
    for (String sql : blockers)
      if (db.scalar(sql, Long.class, merchant) > 0)
        throw new DomainException(
            409,
            "Closure is blocked by balances, pending work, evidence or period controls. Inspect the"
                + " merchant financial and reconciliation pages.");
    if (db.scalar(
            "select count(*) from economic_source s where s.merchant_id=? and"
                + " (abs(s.amount_minor::numeric)<>(select coalesce(sum(a.amount_minor),0) from"
                + " allocation a join match_group g on g.id=a.group_id join statement_line l on"
                + " l.id=a.line_id where a.source_id=s.id and g.status='MATCHED' and l.kind=case"
                + " when s.event_type in('SETTLEMENT','PAYOUT','BATCH_ZERO') then"
                + " 'PROCESSOR_TRANSFER' else 'PROCESSOR_ECONOMIC' end))",
            Long.class,
            merchant)
        > 0)
      throw new DomainException(409, "Sources need complete processor evidence before closure.");
    if (db.scalar(
            "select count(*) from economic_source s where s.merchant_id=? and s.event_type"
                + " in('SETTLEMENT','PAYOUT') and abs(s.amount_minor::numeric)<>(select"
                + " coalesce(sum(a.amount_minor),0) from allocation a join match_group g on"
                + " g.id=a.group_id join statement_line l on l.id=a.line_id where a.source_id=s.id"
                + " and g.status='MATCHED' and l.kind='BANK')",
            Long.class,
            merchant)
        > 0)
      throw new DomainException(409, "Transfers need complete bank corroboration before closure.");
    if (db.scalar(
            "select count(*) from settlement_batch b where b.merchant_id=? and ((b.net_minor<>0 and"
                + " not exists(select 1 from bank_transfer t where t.batch_id=b.id and"
                + " t.amount_minor=b.net_minor)) or (b.net_minor=0 and exists(select 1 from"
                + " bank_transfer t where t.batch_id=b.id)))",
            Long.class,
            merchant)
        > 0) throw new DomainException(409, "Settlement transfer controls block closure.");
  }

  /**
   * Internal operator boundary: the caller owns the merchant row before destructive retention work.
   */
  @Transactional
  public void assertClosureReady(UUID merchant) {
    db.lockMerchant(merchant);
    assertClosure(merchant);
  }

  private void assertFinancialDrain(UUID merchant) {
    var control = db.one("select restore_hold from system_control where id=true for update");
    if (Boolean.TRUE.equals(control.get("restore_hold")))
      throw new DomainException(
          409, "Reconcile the restoration hold before requesting or completing closure.");
    String[] blockers = {
      "select count(*) from (select account from ledger_entry where merchant_id=? group by account"
          + " having sum(debit::numeric-credit::numeric)<>0) balances",
      "select count(*) from operation where merchant_id=? and status not"
          + " in('SUCCEEDED','FAILED','CANCELLED')",
      "select count(*) from refund_reservation where merchant_id=? and state='ACTIVE'",
      "select count(*) from payout where merchant_id=? and (reserved or status not"
          + " in('SUCCEEDED','FAILED','CANCELLED','REJECTED'))",
      "select count(*) from dispute where merchant_id=? and status not in('WON','LOST')",
      "select count(*) from economic_source where merchant_id=? and (not recognized or"
          + " (amount_minor<>0 and not exists(select 1 from journal j where"
          + " j.source_id=economic_source.id)))",
      "select count(*) from durable_job where merchant_id=? and kind='PROVIDER_HISTORY' and state"
          + " not in('DONE','CANCELLED')",
      "select count(*) from callback_inbox where state='ACCEPTED' and"
          + " payload::jsonb->>'provider_account_id'=(select provider_account_id::text from"
          + " merchant where id=?)"
    };
    for (String sql : blockers)
      if (db.scalar(sql, Long.class, merchant) > 0)
        throw new DomainException(
            409,
            "Closure requires zero balances and completed economic duties. Finish accepted work,"
                + " recognize corrections, flush settlement batches and drain settled funds while"
                + " the merchant is ACTIVE. Operations can resume a suspended merchant when"
                + " appropriate; then request closure again.");
    assertBatchControls(merchant);
  }

  private void assertBatchControls(UUID merchant) {
    // Expand each immutable batch exactly once, then compare both directions of membership.
    String controls =
        "with batches as materialized (select b.*,m.provider_account_id from settlement_batch b"
            + " join merchant m on m.id=b.merchant_id where b.merchant_id=?), details as"
            + " materialized (select b.id,b.provider_id,b.net_minor,b.transfer_reference,d.value as"
            + " item from batches b cross join lateral jsonb_array_elements(b.sources_json::jsonb)"
            + " d), sources as materialized (select * from economic_source where merchant_id=?)"
            + " select (select count(*) from sources s where s.event_type<>'PAYOUT' and (s.batch_id"
            + " is null or not exists(select 1 from details d where d.provider_id=s.batch_id and"
            + " d.item->>'source_id'=s.provider_source_id and d.item->>'event_type'=s.event_type"
            + " and (d.item->>'amount_minor')::bigint=s.amount_minor and"
            + " d.item->>'batch_id'=s.batch_id and d.item->>'resource_id'=s.resource_id::text and"
            + " (d.item->>'occurred_at')::timestamptz=s.occurred_at))) + (select count(*) from"
            + " details d where not exists(select 1 from sources s where s.batch_id=d.provider_id"
            + " and s.provider_source_id=d.item->>'source_id' and"
            + " s.event_type=d.item->>'event_type' and"
            + " s.amount_minor=(d.item->>'amount_minor')::bigint and"
            + " s.resource_id::text=d.item->>'resource_id' and"
            + " s.occurred_at=(d.item->>'occurred_at')::timestamptz)) + (select count(*) from"
            + " batches b where (select coalesce(sum((d.item->>'amount_minor')::numeric),0) from"
            + " details d where d.id=b.id and d.item->>'event_type' not"
            + " in('SETTLEMENT','PAYOUT','BATCH_ZERO'))<>b.net_minor or (select count(*) from"
            + " details d where d.id=b.id)<>(select count(distinct"
            + " (d.item->>'source_id',d.item->>'event_type')) from details d where d.id=b.id) or"
            + " exists(select 1 from details d where d.id=b.id and"
            + " ((d.item->>'provider_account_id')::uuid is distinct from b.provider_account_id or"
            + " d.item->>'currency' is distinct from 'USD' or d.item->>'batch_id' is distinct from"
            + " b.provider_id)) or (b.net_minor=0 and (b.transfer_reference<>'' or exists(select 1"
            + " from bank_transfer t where t.batch_id=b.id) or (select count(*) from details d"
            + " where d.id=b.id and d.item->>'event_type'='BATCH_ZERO' and"
            + " d.item->>'source_id'=b.provider_id and (d.item->>'amount_minor')::bigint=0)<>1 or"
            + " exists(select 1 from details d where d.id=b.id and"
            + " d.item->>'event_type'='SETTLEMENT'))) or (b.net_minor<>0 and ((select count(*) from"
            + " bank_transfer t where t.batch_id=b.id and t.merchant_id=b.merchant_id and"
            + " t.reference=b.transfer_reference and t.amount_minor=b.net_minor)<>1 or (select"
            + " count(*) from details d where d.id=b.id and d.item->>'event_type'='SETTLEMENT' and"
            + " d.item->>'source_id'=b.transfer_reference and"
            + " (d.item->>'amount_minor')::bigint=b.net_minor)<>1 or exists(select 1 from details d"
            + " where d.id=b.id and d.item->>'event_type'='BATCH_ZERO'))))";
    if (db.scalar(controls, Long.class, merchant, merchant) > 0)
      throw new DomainException(
          409,
          "Closure requires every economic source to belong to an exact sealed settlement batch,"
              + " including zero batches, with matching membership, net and transfer controls."
              + " Flush and reconcile outstanding batches before requesting closure.");
  }

  private static long version(Map<String, String> form) {
    try {
      return Long.parseLong(form.get("expected_version"));
    } catch (Exception e) {
      throw new DomainException(400, "A valid expected version is required.");
    }
  }

  private static String role(String role) {
    if (!java.util.Set.of("OWNER", "FINANCE", "ANALYST").contains(role == null ? "" : role))
      throw new DomainException(400, "Choose OWNER, FINANCE or ANALYST.");
    return role;
  }
}
