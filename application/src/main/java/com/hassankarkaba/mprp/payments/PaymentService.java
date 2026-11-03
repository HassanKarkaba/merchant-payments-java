package com.hassankarkaba.mprp.payments;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.*;
import com.hassankarkaba.mprp.protocol.*;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
  private final Db db;
  private final Authz auth;
  private final OperationService operations;
  private final AuditService audit;

  public PaymentService(Db db, Authz auth, OperationService operations, AuditService audit) {
    this.db = db;
    this.auth = auth;
    this.operations = operations;
    this.audit = audit;
  }

  public static String secret() {
    byte[] data = new byte[32];
    new SecureRandom().nextBytes(data);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
  }

  public static String digest(String input) {
    return CanonicalJson.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Transactional
  public String link(UUID merchant, String amount, String mode, int minutes) {
    UUID actor = auth.require(merchant, "OWNER", "FINANCE");
    long cents = Money.parse(amount, 100, 100000000);
    require(Set.of("AUTOMATIC", "MANUAL").contains(mode), 400, "Choose a valid capture mode");
    require(minutes >= 5 && minutes <= 10080, 400, "Expiry must be 5–10080 minutes");
    db.lockMerchant(merchant);
    require(
        "ACTIVE"
            .equals(db.scalar("select status from merchant where id=?", String.class, merchant)),
        409,
        "Merchant must be active");
    require(
        db.scalar(
                "select count(*) from payment_link where merchant_id=? and consumed_at is null and"
                    + " revoked_at is null and expires_at>now()",
                Long.class,
                merchant)
            < 20,
        409,
        "Active payment link limit reached");
    String token = secret();
    UUID id = UUID.randomUUID();
    db.exec(
        "insert into payment_link(id,merchant_id,token_digest,amount_minor,capture_mode,expires_at)"
            + " values(?,?,?,?,?,?)",
        id,
        merchant,
        digest(token),
        cents,
        mode,
        Timestamp.from(Instant.now().plusSeconds(minutes * 60L)));
    audit.record(merchant, actor, "payment.link.created", id, "Synthetic USD");
    return token;
  }

  @Transactional
  public void revoke(UUID merchant, UUID link) {
    UUID actor = auth.require(merchant, "OWNER", "FINANCE");
    db.lockMerchant(merchant);
    require(
        db.exec(
                "update payment_link set revoked_at=coalesce(revoked_at,now()) where id=? and"
                    + " merchant_id=?",
                link,
                merchant)
            == 1,
        404,
        "Link not found");
    audit.record(merchant, actor, "payment.link.revoked", link, "");
  }

  public Map<String, Object> linkInfo(String token) {
    return db.one(
        "select"
            + " l.id,l.merchant_id,l.amount_minor,l.capture_mode,l.expires_at,l.consumed_at,l.revoked_at,m.name"
            + " from payment_link l join merchant m on m.id=l.merchant_id where token_digest=?",
        digest(token));
  }

  @Transactional
  public UUID checkout(
      String token, String browserSecret, String attempt, String customer, String paymentToken) {
    require(
        Set.of(
                "tok_demo_success",
                "tok_demo_decline",
                "tok_demo_timeout_after_commit",
                "tok_demo_requires_review")
            .contains(paymentToken),
        400,
        "Choose a fictional simulator token");
    customer = Normalizer.normalize(customer.trim(), Normalizer.Form.NFC);
    require(
        !customer.isEmpty()
            && customer.codePointCount(0, customer.length()) <= 100
            && !java.util.regex.Pattern.compile("(?:[0-9][ -]?){12,19}").matcher(customer).find(),
        400,
        "Enter a fictional customer name up to 100 characters");
    require(attempt.matches("[A-Za-z0-9_-]{32,128}"), 400, "Invalid checkout attempt");
    var link = linkInfo(token);
    UUID merchant = Db.uuid(link, "merchant_id");
    db.lockMerchant(merchant);
    link = db.one("select * from payment_link where id=? for update", link.get("id"));
    String fingerprint =
        CanonicalJson.sha256(Map.of("customer_name", customer, "token", paymentToken));
    var session =
        db.maybe(
            "select * from checkout_session where link_id=? and secret_digest=? for update",
            link.get("id"),
            digest(browserSecret));
    if (session.isPresent() && session.get().get("payment_id") != null) {
      require(
          fingerprint.equals(session.get().get("fingerprint"))
              && attempt.equals(session.get().get("attempt_key")),
          409,
          "Checkout attempt changed");
      return Db.uuid(session.get(), "id");
    }
    require(
        link.get("consumed_at") == null
            && link.get("revoked_at") == null
            && ((Timestamp) link.get("expires_at")).toInstant().isAfter(Instant.now()),
        409,
        "This link is unavailable");
    UUID payment = UUID.randomUUID(), receipt = UUID.randomUUID();
    long amount = Db.number(link, "amount_minor");
    db.exec(
        "insert into payment(id,merchant_id,link_id,capture_mode,customer_name) values(?,?,?,?,?)",
        payment,
        merchant,
        link.get("id"),
        link.get("capture_mode"),
        customer);
    db.exec(
        "insert into checkout_session(id,link_id,secret_digest,attempt_key,fingerprint,payment_id)"
            + " values(?,?,?,?,?,?)",
        receipt,
        link.get("id"),
        digest(browserSecret),
        attempt,
        fingerprint,
        payment);
    db.exec("update payment_link set consumed_at=now() where id=?", link.get("id"));
    operations.admit(
        merchant,
        receipt,
        "authorize",
        payment,
        attempt,
        Map.of(
            "amount_minor",
            amount,
            "token",
            paymentToken,
            "capture_mode",
            link.get("capture_mode"),
            "customer_name",
            customer));
    return receipt;
  }

  public Map<String, Object> receipt(UUID session, String browserSecret) {
    return db.one(
        "select p.*,s.id as receipt_id,o.status as execution_state,o.id as operation_id from"
            + " checkout_session s join payment p on p.id=s.payment_id left join operation o on"
            + " o.resource_id=p.id and o.kind='authorize' where s.id=? and s.secret_digest=?",
        session,
        digest(browserSecret));
  }

  @Transactional
  public UUID action(
      UUID merchant, UUID payment, String kind, String amount, String reason, String key) {
    UUID actor = auth.require(merchant, "OWNER", "FINANCE");
    long cents =
        kind.equals("void") ? 0 : Money.parse(amount, kind.equals("refund") ? 1 : 100, 100000000);
    reason = reason == null ? "" : Normalizer.normalize(reason.trim(), Normalizer.Form.NFC);
    if (kind.equals("refund"))
      require(
          !reason.isEmpty() && reason.codePointCount(0, reason.length()) <= 2000,
          400,
          "Refund reason is required and limited to 2000 characters");
    var payload = new LinkedHashMap<String, Object>();
    payload.put("amount_minor", cents);
    if (kind.equals("refund")) payload.put("reason", reason);
    db.lockMerchant(merchant);
    var p =
        db.one("select * from payment where id=? and merchant_id=? for update", payment, merchant);
    var replay =
        db.maybe(
            "select * from operation where merchant_id=? and actor_id=? and kind=? and"
                + " resource_id=? and action_key=?",
            merchant,
            actor,
            kind,
            payment,
            key);
    if (replay.isPresent()) {
      require(
          CanonicalJson.sha256(payload).equals(replay.get().get("fingerprint")),
          409,
          "Action identity changed");
      return Db.uuid(replay.get(), "id");
    }
    if (kind.equals("capture") || kind.equals("void")) {
      require("AUTHORIZED".equals(p.get("status")), 409, "Payment is not authorized");
      require(
          p.get("authorization_expires_at") != null
              && ((Timestamp) p.get("authorization_expires_at")).toInstant().isAfter(Instant.now()),
          409,
          "Authorization has expired");
      require(
          db.scalar(
                  "select count(*) from operation where resource_id=? and kind in('capture','void')"
                      + " and status not in('FAILED','CANCELLED')",
                  Long.class,
                  payment)
              == 0,
          409,
          "A final capture or void was already admitted");
      if (kind.equals("capture"))
        require(
            cents <= Db.number(p, "authorized_minor"), 400, "Capture exceeds authorized principal");
    } else if (kind.equals("refund")) {
      require("CAPTURED".equals(p.get("status")), 409, "Payment has not been captured");
      require(
          db.scalar(
                  "select count(*) from dispute where payment_id=? and status"
                      + " in('OPEN','RESPONDED')",
                  Long.class,
                  payment)
              == 0,
          409,
          "An open dispute blocks refunds");
      require(
          cents
              <= Db.number(p, "captured_minor")
                  - Db.number(p, "refunded_minor")
                  - Db.number(p, "reserved_refund_minor")
                  - Db.number(p, "disputed_minor"),
          409,
          "Refund exceeds available principal");
      require(
          db.scalar(
                  "select count(*) from refund_reservation where payment_id=? and state"
                      + " in('ACTIVE','SUCCEEDED')",
                  Long.class,
                  payment)
              < 100,
          409,
          "Refund count limit reached");
    } else throw new DomainException(400, "Unsupported payment action");
    UUID operation = operations.admit(merchant, actor, kind, payment, key, payload);
    if (kind.equals("refund")) {
      db.exec(
          "insert into refund_reservation(id,operation_id,payment_id,merchant_id,amount_minor)"
              + " values(?,?,?,?,?)",
          UUID.randomUUID(),
          operation,
          payment,
          merchant,
          cents);
      db.exec(
          "update payment set reserved_refund_minor=reserved_refund_minor+?,version=version+1 where"
              + " id=?",
          cents,
          payment);
    }
    return operation;
  }

  public void applyResult(UUID merchant, Map<String, Object> op, Map<String, Object> result) {
    UUID payment = Db.uuid(op, "resource_id");
    String kind = op.get("kind").toString();
    if (!Set.of("authorize", "capture", "void", "refund").contains(kind)) return;
    var p =
        db.one("select * from payment where id=? and merchant_id=? for update", payment, merchant);
    String state = result.get("state").toString();
    if (state.equals("NO_EFFECT") || state.equals("FAILED")) {
      operations.releaseRefund(Db.uuid(op, "id"));
      if (kind.equals("authorize"))
        db.exec(
            "update payment set status=?,version=version+1 where id=? and status='PROCESSING'",
            result.getOrDefault("business_state", "CANCELLED"),
            payment);
      return;
    }
    long version = ((Number) result.getOrDefault("version", 0)).longValue();
    if (version >= Db.number(p, "provider_version")) {
      long
          captured =
              ((Number) result.getOrDefault("captured_minor", p.get("captured_minor"))).longValue(),
          refunded =
              ((Number) result.getOrDefault("refunded_minor", p.get("refunded_minor"))).longValue();
      if (kind.equals("refund")) operations.releaseRefund(Db.uuid(op, "id"));
      String expires = (String) result.get("authorization_expires_at");
      db.exec(
          "update payment set"
              + " status=?,authorized_minor=?,captured_minor=?,refunded_minor=?,authorization_expires_at=coalesce(?,authorization_expires_at),provider_version=?,version=version+1"
              + " where id=?",
          result.get("business_state"),
          result.getOrDefault("authorized_minor", p.get("authorized_minor")),
          captured,
          refunded,
          expires == null ? null : Timestamp.from(Instant.parse(expires)),
          version,
          payment);
      if (kind.equals("refund"))
        db.exec(
            "update refund_reservation set state='SUCCEEDED' where operation_id=?", op.get("id"));
    } else if (kind.equals("refund")) {
      operations.releaseRefund(Db.uuid(op, "id"));
      db.exec("update refund_reservation set state='SUCCEEDED' where operation_id=?", op.get("id"));
    }
  }
}
