package com.hassankarkaba.mprp.simulator;

import com.hassankarkaba.mprp.protocol.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Independent provider authority. One control lock serializes provider commitments and tombstones.
 */
@Service
public class ProviderStore {
  private final JdbcTemplate db;
  private final UUID environment;

  public ProviderStore(JdbcTemplate db, @Value("${mprp.environment-id}") UUID environment) {
    this.db = db;
    this.environment = environment;
  }

  static Map<String, Object> map(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  public int exec(String sql, Object... args) {
    return db.update(sql, args);
  }

  List<Map<String, Object>> rows(String sql, Object... args) {
    return db.queryForList(sql, args);
  }

  Map<String, Object> one(String sql, Object... args) {
    List<Map<String, Object>> result = rows(sql, args);
    if (result.isEmpty())
      throw new ProviderException(404, "not_found", "Synthetic provider record not found");
    return result.getFirst();
  }

  long count(String sql, Object... args) {
    return db.queryForObject(sql, Long.class, args);
  }

  static long number(Map<String, Object> p, String key, long min, long max) {
    Object value = p.get(key);
    if (!(value instanceof Number n) || value instanceof Double || value instanceof Float)
      throw ProviderException.invalid("Expected integer " + key);
    long v;
    try {
      v = new java.math.BigInteger(n.toString()).longValueExact();
    } catch (ArithmeticException ex) {
      throw ProviderException.invalid("Integer overflow");
    }
    if (v < min || v > max) throw ProviderException.invalid("Integer outside bounds: " + key);
    return v;
  }

  static String text(Map<String, Object> p, String key) {
    Object v = p.get(key);
    if (!(v instanceof String s)) throw ProviderException.invalid("Expected text " + key);
    return s;
  }

  static UUID uuid(Object v) {
    try {
      String s = v.toString();
      UUID u = UUID.fromString(s);
      if (!u.toString().equals(s)) throw new IllegalArgumentException();
      return u;
    } catch (Exception e) {
      throw ProviderException.invalid("Expected canonical UUID");
    }
  }

  static Instant instant(Object v) {
    if (v instanceof Timestamp t) return t.toInstant();
    if (v instanceof Instant i) return i;
    return Instant.parse(v.toString());
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> payload(Map<String, Object> data) {
    Object p = data.get("payload");
    return p instanceof Map<?, ?> ? (Map<String, Object>) p : data;
  }

  private Map<String, Object> control() {
    Map<String, Object> c = one("SELECT * FROM sim_control WHERE id=1 FOR UPDATE");
    if (c.get("environment_id") == null) {
      db.update("UPDATE sim_control SET environment_id=? WHERE id=1", environment);
      c.put("environment_id", environment);
    }
    if (!environment.equals(c.get("environment_id")))
      throw new ProviderException(409, "foreign_environment", "Simulator environment mismatch");
    return c;
  }

  private void envelope(Map<String, Object> data, Map<String, Object> c, boolean allowRestore) {
    if (number(data, "schema_version", 1, 1) != 1
        || !"mprp_v1".equals(text(data, "policy_version")))
      throw ProviderException.invalid("Unsupported protocol policy");
    if (!environment.equals(uuid(data.get("environment_id"))))
      throw new ProviderException(409, "foreign_environment", "Foreign environment");
    if (Boolean.TRUE.equals(c.get("restored")) && !allowRestore)
      throw new ProviderException(
          409, "restore_hold", "Provider restoration requires reconciliation");
    Object main = data.getOrDefault("main_instance_id", data.get("instance_id"));
    if (c.get("main_instance_id") != null && !c.get("main_instance_id").equals(uuid(main)))
      throw new ProviderException(409, "instance_mismatch", "Main instance changed");
    if (c.get("main_instance_id") != null
        && number(data, "dispatch_epoch", 0, Long.MAX_VALUE)
            != ((Number) c.get("dispatch_epoch")).longValue())
      throw new ProviderException(409, "epoch_mismatch", "Dispatch epoch mismatch");
  }

  @Transactional
  public Map<String, Object> handshake(Map<String, Object> data) {
    Map<String, Object> c = control();
    if (!environment.equals(uuid(data.get("environment_id"))))
      throw new ProviderException(409, "foreign_environment", "Environment differs");
    UUID main = uuid(data.getOrDefault("main_instance_id", data.get("instance_id")));
    String run = (String) data.getOrDefault("run_token", main.toString());
    long epoch = number(data, "dispatch_epoch", 1, Long.MAX_VALUE);
    if (count("SELECT count(*) FROM sim_retired_runs WHERE run_token=?", run) > 0)
      throw new ProviderException(409, "retired_run", "Run permanently retired");
    if (c.get("main_instance_id") != null && !c.get("main_instance_id").equals(main)) {
      db.update("UPDATE sim_control SET restored=true WHERE id=1");
      return map(
          "hold",
          true,
          "reason",
          "Main instance mismatch",
          "simulator_instance_id",
          c.get("instance_id"),
          "environment_id",
          environment);
    }
    long old = ((Number) c.get("dispatch_epoch")).longValue();
    if (epoch < old) throw new ProviderException(409, "stale_epoch", "Epoch cannot regress");
    if (c.get("run_token") != null && !c.get("run_token").equals(run)) {
      if (epoch <= old)
        throw new ProviderException(409, "stale_epoch", "Replacement run needs newer epoch");
      db.update(
          "INSERT INTO sim_retired_runs(run_token) VALUES(?) ON CONFLICT DO NOTHING",
          c.get("run_token"));
    }
    db.update(
        "UPDATE sim_control SET main_instance_id=?,run_token=?,dispatch_epoch=? WHERE id=1",
        main,
        run,
        epoch);
    return capabilities();
  }

  public Map<String, Object> capabilities() {
    Map<String, Object> c = one("SELECT * FROM sim_control WHERE id=1");
    return map(
        "schema_version",
        1,
        "policy_version",
        "mprp_v1",
        "environment_id",
        environment,
        "instance_id",
        c.get("instance_id"),
        "simulator_instance_id",
        c.get("instance_id"),
        "dispatch_epoch",
        c.get("dispatch_epoch"),
        "restore_hold",
        c.get("restored"),
        "source_high_water",
        c.get("source_sequence"),
        "event_high_water",
        c.get("event_sequence"),
        "worker_heartbeat",
        c.get("worker_heartbeat") == null
            ? null
            : CanonicalJson.instant(instant(c.get("worker_heartbeat"))),
        "atomic_resolver",
        true,
        "independent_commit",
        true,
        "event_delivery_blocked",
        count("SELECT count(*) FROM sim_event_failure WHERE state='OPEN'") > 0,
        "currency",
        "USD",
        "sandbox",
        true);
  }

  private UUID account(Map<String, Object> data) {
    UUID id = uuid(data.getOrDefault("provider_account_id", data.get("merchant_id")));
    if (count(
            "SELECT count(*) FROM sim_retired_namespaces WHERE account_id=? OR merchant_id=?",
            id,
            id)
        > 0) throw new ProviderException(409, "retired_namespace", "Namespace permanently retired");
    db.update(
        "INSERT INTO sim_accounts(id,merchant_id,environment_id) VALUES(?,?,?) ON CONFLICT DO"
            + " NOTHING",
        id,
        id,
        environment);
    Map<String, Object> a = one("SELECT * FROM sim_accounts WHERE id=? FOR UPDATE", id);
    if (Boolean.TRUE.equals(a.get("retired")))
      throw new ProviderException(409, "retired_namespace", "Account retired");
    return id;
  }

  @Transactional
  public Map<String, Object> mutate(String rawKind, Map<String, Object> data) {
    String kind = kind(rawKind);
    Map<String, Object> c = control();
    envelope(data, c, false);
    String key = text(data, "operation_key");
    if (!key.matches("[A-Za-z0-9_-]{32,128}"))
      throw ProviderException.invalid("Invalid provider key");
    Map<String, Object> p = payload(data);
    String hash = CanonicalJson.sha256(p);
    if (!hash.equals(text(data, "payload_hash")))
      throw new ProviderException(
          409, "payload_hash_mismatch", "Canonical request fingerprint differs");
    List<Map<String, Object>> previous =
        rows("SELECT * FROM sim_operations WHERE operation_key=?", key);
    if (!previous.isEmpty()) {
      Map<String, Object> old = previous.getFirst();
      if ("NO_EFFECT".equals(old.get("state")))
        return CanonicalJson.parse(old.get("result").toString());
      if (!hash.equals(old.get("payload_hash")) || !kind.equals(old.get("kind")))
        throw new ProviderException(
            409, "key_conflict", "Provider key is reserved by another request");
      return CanonicalJson.parse(old.get("result").toString());
    }
    UUID account = account(data),
        resource =
            uuid(
                data.getOrDefault(
                    "resource_id",
                    p.getOrDefault(
                        "resource_id",
                        p.getOrDefault("payment_id", UUID.randomUUID().toString()))));
    Map<String, Object> result;
    try {
      result = perform(kind, key, account, resource, p, c);
    } catch (ProviderException e) {
      if (e.status >= 500) throw e;
      result =
          result(
              c,
              key,
              hash,
              account,
              resource,
              "FAILED",
              "REJECTED",
              map("reason", e.code, "safe_message", e.getMessage(), "sources", List.of()));
    }
    result.put("payload_hash", hash);
    result = boundedResult(result);
    db.update(
        "INSERT INTO"
            + " sim_operations(operation_key,payload_hash,kind,account_id,resource_id,state,payload,result)"
            + " VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb)",
        key,
        hash,
        kind,
        account,
        resource,
        result.get("state"),
        CanonicalJson.string(p),
        CanonicalJson.string(result));
    emit(c, account, kind, result);
    return result;
  }

  private String kind(String k) {
    return switch (k) {
      case "authorizations", "authorize" -> "authorize";
      case "captures", "capture" -> "capture";
      case "voids", "void" -> "void";
      case "refunds", "refund" -> "refund";
      case "payouts", "payout" -> "payout";
      case "disputes/open", "open_dispute" -> "open_dispute";
      case "disputes/respond", "dispute_response" -> "dispute_response";
      case "disputes/resolve", "resolve_dispute" -> "resolve_dispute";
      case "settlements/flush", "flush" -> "flush";
      case "corrections", "correction" -> "correction";
      case "accounts" -> "accounts";
      default -> throw ProviderException.invalid("Unsupported synthetic operation");
    };
  }

  private Map<String, Object> result(
      Map<String, Object> c,
      String key,
      String hash,
      UUID account,
      UUID resource,
      String state,
      String business,
      Map<String, Object> extra) {
    Map<String, Object> r =
        map(
            "schema_version",
            1,
            "policy_version",
            "mprp_v1",
            "environment_id",
            environment,
            "instance_id",
            c.get("instance_id"),
            "simulator_instance_id",
            c.get("instance_id"),
            "operation_key",
            key,
            "payload_hash",
            hash,
            "provider_account_id",
            account,
            "resource_id",
            resource,
            "currency",
            "USD",
            "state",
            state,
            "business_state",
            business,
            "outcome_known",
            true,
            "version",
            1,
            "sources",
            List.of());
    r.putAll(extra);
    return r;
  }

  private UUID batch(UUID account) {
    List<Map<String, Object>> open =
        rows("SELECT * FROM sim_batches WHERE account_id=? AND state='OPEN'", account);
    if (!open.isEmpty()) {
      Map<String, Object> b = open.getFirst();
      LocalDate day = instant(b.get("created_at")).atZone(ZoneOffset.UTC).toLocalDate();
      if (day.equals(LocalDate.now(ZoneOffset.UTC))) return (UUID) b.get("id");
      Map<String, Object> completed =
          completeBatch(
              (UUID) b.get("id"), "daily_" + day, one("SELECT * FROM sim_control WHERE id=1"));
      emitSettlement(account, completed);
    }
    UUID id = UUID.randomUUID();
    db.update("INSERT INTO sim_batches(id,account_id,state) VALUES(?,?,'OPEN')", id, account);
    return id;
  }

  private Map<String, Object> source(
      Map<String, Object> c,
      UUID account,
      UUID resource,
      UUID payment,
      String type,
      long amount,
      UUID batch,
      boolean clearing,
      String reason) {
    if (amount == 0 && !type.equals("BATCH_ZERO"))
      throw ProviderException.invalid("Zero economic source");
    UUID id = type.equals("BATCH_ZERO") ? batch : UUID.randomUUID();
    long seq =
        ((Number)
                one("UPDATE sim_control SET source_sequence=source_sequence+1 WHERE id=1 RETURNING"
                        + " source_sequence")
                    .get("source_sequence"))
            .longValue();
    Instant at = Instant.now();
    String ref = id.toString();
    db.update(
        "INSERT INTO"
            + " sim_sources(source_id,sequence,account_id,resource_id,payment_id,event_type,amount_minor,occurred_at,reference,batch_id,clearing_line,reason)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        seq,
        account,
        resource,
        payment,
        type,
        amount,
        Timestamp.from(at),
        ref,
        batch,
        clearing,
        reason);
    return sourceDto(one("SELECT * FROM sim_sources WHERE source_id=?", id));
  }

  Map<String, Object> sourceDto(Map<String, Object> s) {
    return map(
        "source_id",
        s.get("source_id"),
        "sequence",
        s.get("sequence"),
        "provider_account_id",
        s.get("account_id"),
        "resource_id",
        s.get("resource_id"),
        "payment_id",
        s.get("payment_id"),
        "event_type",
        s.get("event_type"),
        "amount_minor",
        s.get("amount_minor"),
        "currency",
        "USD",
        "occurred_at",
        CanonicalJson.instant(instant(s.get("occurred_at"))),
        "reference",
        s.get("reference"),
        "batch_id",
        s.get("batch_id"),
        "clearing_line",
        s.get("clearing_line"));
  }

  private Map<String, Object> payment(UUID account, UUID resource) {
    return one(
        "SELECT * FROM sim_payments WHERE account_id=? AND id=? FOR UPDATE", account, resource);
  }

  private Map<String, Object> paymentSnapshot(Map<String, Object> payment) {
    UUID id = (UUID) payment.get("id");
    return map(
        "version",
        payment.get("version"),
        "business_state",
        payment.get("state"),
        "authorized_minor",
        payment.get("authorized_minor"),
        "captured_minor",
        payment.get("captured_minor"),
        "refunded_minor",
        payment.get("refunded_minor"),
        "fee_minor",
        payment.get("fee_minor"),
        "authorization_expires_at",
        CanonicalJson.instant(instant(payment.get("authorization_expires_at"))),
        "sources",
        rows(
                "SELECT * FROM sim_sources WHERE payment_id=? OR resource_id=? ORDER BY sequence",
                id,
                id)
            .stream()
            .map(this::sourceDto)
            .toList());
  }

  private Map<String, Object> perform(
      String kind,
      String key,
      UUID account,
      UUID resource,
      Map<String, Object> p,
      Map<String, Object> c) {
    String hash = CanonicalJson.sha256(p);
    Instant now = Instant.now();
    Map<String, Object> extra = new LinkedHashMap<>();
    String business = "COMPLETED";
    switch (kind) {
      case "accounts" -> {
        extra = accountSnapshot(account);
      }
      case "authorize" -> {
        long amount =
            number(
                p, p.containsKey("amount_minor") ? "amount_minor" : "amount_cents", 100, 100000000);
        String token = (String) p.getOrDefault("token", p.get("scenario"));
        if (!Set.of(
                "tok_demo_success",
                "tok_demo_decline",
                "tok_demo_timeout_after_commit",
                "tok_demo_requires_review")
            .contains(token)) throw ProviderException.invalid("Only synthetic tokens are accepted");
        if ("tok_demo_decline".equals(token) || "tok_demo_requires_review".equals(token)) {
          business = "tok_demo_decline".equals(token) ? "DECLINED" : "REJECTED";
          break;
        }
        String mode = (String) p.getOrDefault("capture_mode", "AUTOMATIC");
        if (!Set.of("AUTOMATIC", "MANUAL").contains(mode))
          throw ProviderException.invalid("Capture mode invalid");
        db.update(
            "INSERT INTO"
                + " sim_payments(id,account_id,authorized_minor,state,authorization_expires_at)"
                + " VALUES(?,?,?,'AUTHORIZED',?)",
            resource,
            account,
            amount,
            Timestamp.from(now.plus(Duration.ofDays(7))));
        if ("AUTOMATIC".equals(mode)) capture(account, resource, amount, c);
        extra = paymentSnapshot(payment(account, resource));
        business = (String) extra.get("business_state");
      }
      case "capture" -> {
        Map<String, Object> pay = payment(account, resource);
        long amount =
            number(p, "amount_minor", 100, ((Number) pay.get("authorized_minor")).longValue());
        capture(account, resource, amount, c);
        extra = paymentSnapshot(payment(account, resource));
        business = "CAPTURED";
      }
      case "void" -> {
        Map<String, Object> pay = payment(account, resource);
        if (!"AUTHORIZED".equals(pay.get("state"))
            || !now.isBefore(instant(pay.get("authorization_expires_at"))))
          throw new ProviderException(
              409, "authorization_unavailable", "Authorization unavailable");
        db.update("UPDATE sim_payments SET state='VOIDED',version=version+1 WHERE id=?", resource);
        extra = paymentSnapshot(payment(account, resource));
        business = "VOIDED";
      }
      case "refund" -> {
        Map<String, Object> pay = payment(account, resource);
        long available =
            Math.subtractExact(
                ((Number) pay.get("captured_minor")).longValue(),
                ((Number) pay.get("refunded_minor")).longValue());
        long amount = number(p, "amount_minor", 1, 100000000);
        if (!"CAPTURED".equals(pay.get("state"))
            || amount > available - disputedPrincipal(resource)
            || count(
                    "SELECT count(*) FROM sim_disputes WHERE payment_id=? AND state"
                        + " IN('OPEN','RESPONDED')",
                    resource)
                > 0)
          throw new ProviderException(409, "refund_unavailable", "Refund principal unavailable");
        source(
            c,
            account,
            resource,
            resource,
            "REFUND",
            -amount,
            batch(account),
            true,
            Objects.toString(p.get("reason"), ""));
        db.update(
            "UPDATE sim_payments SET refunded_minor=refunded_minor+?,version=version+1 WHERE id=?",
            amount,
            resource);
        extra = paymentSnapshot(payment(account, resource));
        business = "CAPTURED";
      }
      case "payout" -> {
        long amount = number(p, "amount_minor", 1, 100000000);
        String beneficiary =
            text(p, p.containsKey("beneficiary_token") ? "beneficiary_token" : "beneficiary");
        if (!beneficiary.matches("acct_demo_[a-f0-9]{32}")
            || count(
                    "SELECT count(*) FROM sim_accounts WHERE id=? AND retired=false",
                    uuidFromToken(beneficiary))
                == 0) throw ProviderException.invalid("Unknown synthetic beneficiary");
        Map<String, Object> a = one("SELECT * FROM sim_accounts WHERE id=? FOR UPDATE", account);
        if (((Number) a.get("bank_balance_minor")).longValue() < amount)
          throw new ProviderException(409, "insufficient_bank_funds", "Insufficient settled funds");
        Map<String, Object> s =
            source(c, account, resource, null, "PAYOUT", -amount, null, false, "");
        db.update(
            "UPDATE sim_accounts SET bank_balance_minor=bank_balance_minor-? WHERE id=?",
            amount,
            account);
        UUID transfer = UUID.randomUUID();
        db.update(
            "INSERT INTO"
                + " sim_transfers(id,account_id,source_id,kind,amount_minor,reference,occurred_at,beneficiary_token)"
                + " VALUES(?,?,?,'PAYOUT',?,?,?,?)",
            transfer,
            account,
            uuid(s.get("source_id")),
            -amount,
            s.get("reference"),
            Timestamp.from(now),
            beneficiary);
        extra =
            map(
                "amount_minor",
                amount,
                "beneficiary_token",
                beneficiary,
                "beneficiary",
                beneficiary,
                "payout_id",
                resource,
                "sources",
                List.of(s),
                "transfer_id",
                transfer);
        business = "PAID";
      }
      case "flush" -> {
        extra = flush(account, p, c);
        extra.put("batches", List.of(new LinkedHashMap<>(extra)));
        business = "COMPLETED";
      }
      case "open_dispute" -> {
        UUID paymentId = uuid(p.getOrDefault("payment_id", resource.toString()));
        Map<String, Object> pay = payment(account, paymentId);
        long captured = ((Number) pay.get("captured_minor")).longValue(),
            refunded = ((Number) pay.get("refunded_minor")).longValue();
        long amount =
            p.containsKey("amount_minor")
                ? number(p, "amount_minor", 1, 100000000)
                : captured - refunded;
        if (amount <= 0 || amount > captured - refunded)
          throw new ProviderException(409, "dispute_unavailable", "Dispute principal unavailable");
        String outcome = Objects.toString(p.getOrDefault("outcome", "LOST"));
        if (!Set.of("WON", "LOST").contains(outcome))
          throw ProviderException.invalid("Invalid synthetic outcome");
        UUID dispute = p.containsKey("dispute_id") ? uuid(p.get("dispute_id")) : UUID.randomUUID();
        db.update(
            "INSERT INTO"
                + " sim_disputes(id,payment_id,account_id,principal_minor,state,opened_at,deadline,selected_outcome)"
                + " VALUES(?,?,?,?,'OPEN',?,?,?)",
            dispute,
            paymentId,
            account,
            amount,
            Timestamp.from(now),
            Timestamp.from(now.plus(Duration.ofDays(7))),
            outcome);
        source(c, account, dispute, paymentId, "DISPUTE_OPEN", -amount, batch(account), true, "");
        extra = disputeSnapshot(dispute);
        business = "OPEN";
      }
      case "dispute_response" -> {
        Map<String, Object> dispute =
            one(
                "SELECT * FROM sim_disputes WHERE account_id=? AND id=? FOR UPDATE",
                account,
                resource);
        if (!Set.of("OPEN", "RESPONDED").contains(dispute.get("state"))
            || !now.isBefore(instant(dispute.get("deadline"))))
          throw new ProviderException(409, "dispute_deadline", "Response deadline elapsed");
        String response = text(p, p.containsKey("response") ? "response" : "text").trim();
        if (response.isEmpty() || response.length() > 2000)
          throw ProviderException.invalid("Response length invalid");
        db.update(
            "UPDATE sim_disputes SET state='RESPONDED',response=?,version=version+1 WHERE id=?",
            response,
            resource);
        extra = disputeSnapshot(resource);
        business = "RESPONDED";
      }
      case "resolve_dispute" -> {
        Map<String, Object> dispute =
            one(
                "SELECT * FROM sim_disputes WHERE account_id=? AND id=? FOR UPDATE",
                account,
                resource);
        if (Set.of("WON", "LOST").contains(dispute.get("state"))) {
          extra = disputeSnapshot(resource);
          business = (String) dispute.get("state");
          break;
        }
        String outcome =
            Objects.toString(p.getOrDefault("outcome", dispute.get("selected_outcome")));
        if (!Set.of("WON", "LOST").contains(outcome))
          throw ProviderException.invalid("Invalid outcome");
        resolveDispute(dispute, outcome, c);
        extra = disputeSnapshot(resource);
        business = outcome;
      }
      case "correction" -> {
        long amount = number(p, "amount_minor", -100000000, 100000000);
        if (amount == 0) throw ProviderException.invalid("Correction cannot be zero");
        Map<String, Object> s =
            source(
                c,
                account,
                resource,
                null,
                amount > 0 ? "CORRECTION_CREDIT" : "CORRECTION_DEBIT",
                amount,
                batch(account),
                true,
                Objects.toString(p.get("reason"), ""));
        extra = map("amount_minor", amount, "sources", List.of(s));
        business = "UNRECOGNIZED";
      }
      default -> throw ProviderException.invalid("Unsupported operation");
    }
    return result(c, key, hash, account, resource, "SUCCEEDED", business, extra);
  }

  private long disputedPrincipal(UUID payment) {
    return new java.math.BigDecimal(
            one(
                    "SELECT COALESCE(sum(principal_minor),0) AS n FROM sim_disputes WHERE"
                        + " payment_id=? AND state IN('OPEN','RESPONDED','LOST')",
                    payment)
                .get("n")
                .toString())
        .longValueExact();
  }

  private UUID uuidFromToken(String token) {
    String s = token.substring(10);
    return UUID.fromString(
        s.substring(0, 8)
            + "-"
            + s.substring(8, 12)
            + "-"
            + s.substring(12, 16)
            + "-"
            + s.substring(16, 20)
            + "-"
            + s.substring(20));
  }

  private void capture(UUID account, UUID resource, long amount, Map<String, Object> c) {
    Map<String, Object> pay = payment(account, resource);
    if (!"AUTHORIZED".equals(pay.get("state"))
        || !Instant.now().isBefore(instant(pay.get("authorization_expires_at")))
        || amount > ((Number) pay.get("authorized_minor")).longValue())
      throw new ProviderException(
          409, "authorization_unavailable", "Authorization cannot be captured");
    long fee = Money.fee(amount);
    UUID b = batch(account);
    source(c, account, resource, resource, "CAPTURE", amount, b, true, "");
    source(c, account, resource, resource, "FEE", -fee, b, true, "");
    db.update(
        "UPDATE sim_payments SET captured_minor=?,fee_minor=?,state='CAPTURED',version=version+1"
            + " WHERE id=?",
        amount,
        fee,
        resource);
  }

  private Map<String, Object> flush(UUID account, Map<String, Object> p, Map<String, Object> c) {
    String identity =
        Objects.toString(
            p.getOrDefault("cutoff_identity", LocalDate.now(ZoneOffset.UTC).toString()));
    if (identity.length() > 128) throw ProviderException.invalid("Cutoff identity too long");
    List<Map<String, Object>> old =
        rows(
            "SELECT * FROM sim_batches WHERE account_id=? AND cutoff_identity=?",
            account,
            identity);
    if (!old.isEmpty()) return batchSnapshot((UUID) old.getFirst().get("id"));
    return completeBatch(batch(account), identity, c);
  }

  private Map<String, Object> completeBatch(UUID b, String identity, Map<String, Object> c) {
    Map<String, Object> batch = one("SELECT * FROM sim_batches WHERE id=? FOR UPDATE", b);
    if ("COMPLETED".equals(batch.get("state"))) return batchSnapshot(b);
    UUID account = (UUID) batch.get("account_id");
    long net =
        new java.math.BigDecimal(
                one(
                        "SELECT COALESCE(sum(amount_minor),0) AS net FROM sim_sources WHERE"
                            + " batch_id=? AND clearing_line=true",
                        b)
                    .get("net")
                    .toString())
            .longValueExact();
    db.update(
        "UPDATE sim_batches SET cutoff_identity=?,net_minor=?,state='COMPLETED',closed_at=now()"
            + " WHERE id=?",
        identity,
        net,
        b);
    if (net != 0) {
      UUID transfer = UUID.randomUUID();
      Map<String, Object> source = source(c, account, b, null, "SETTLEMENT", net, b, false, "");
      db.update(
          "INSERT INTO"
              + " sim_transfers(id,account_id,source_id,batch_id,kind,amount_minor,reference,occurred_at)"
              + " VALUES(?,?,?,?,'SETTLEMENT',?,?,now())",
          transfer,
          account,
          uuid(source.get("source_id")),
          b,
          net,
          source.get("reference"));
      db.update(
          "UPDATE sim_accounts SET bank_balance_minor=bank_balance_minor+? WHERE id=?",
          net,
          account);
    } else {
      source(c, account, b, null, "BATCH_ZERO", 0, b, false, "");
    }
    return batchSnapshot(b);
  }

  private void emitSettlement(UUID account, Map<String, Object> batch) {
    Map<String, Object> c = one("SELECT * FROM sim_control WHERE id=1");
    UUID id = (UUID) batch.get("batch_id");
    Map<String, Object> extra = new LinkedHashMap<>(batch);
    extra.put("batches", List.of(new LinkedHashMap<>(batch)));
    Map<String, Object> result =
        result(
            c,
            "system_daily_" + id.toString().replace("-", ""),
            "",
            account,
            id,
            "SUCCEEDED",
            "COMPLETED",
            extra);
    emit(c, account, "settlement_completed", result);
  }

  @Transactional
  public void dailySettlements() {
    Map<String, Object> c = control();
    if (Boolean.TRUE.equals(c.get("restored"))) return;
    Instant cutoff = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    for (var b :
        rows(
            "SELECT b.* FROM sim_batches b JOIN sim_accounts a ON a.id=b.account_id WHERE"
                + " b.state='OPEN' AND b.created_at<? AND NOT a.retired ORDER BY b.account_id,b.id"
                + " LIMIT 100 FOR UPDATE OF b",
            Timestamp.from(cutoff))) {
      LocalDate day = instant(b.get("created_at")).atZone(ZoneOffset.UTC).toLocalDate();
      Map<String, Object> completed = completeBatch((UUID) b.get("id"), "daily_" + day, c);
      emitSettlement((UUID) b.get("account_id"), completed);
    }
  }

  public Map<String, Object> accountSnapshot(UUID account) {
    Map<String, Object> a = one("SELECT * FROM sim_accounts WHERE id=?", account);
    String hex = account.toString().replace("-", "");
    return map(
        "provider_account_id",
        account,
        "merchant_id",
        a.get("merchant_id"),
        "bank_account_id",
        account.toString(),
        "beneficiary_token",
        "acct_demo_" + hex,
        "bank_balance_minor",
        a.get("bank_balance_minor"),
        "currency",
        "USD",
        "retired",
        a.get("retired"));
  }

  private Map<String, Object> batchSnapshot(UUID id) {
    Map<String, Object> b = one("SELECT * FROM sim_batches WHERE id=?", id);
    List<Map<String, Object>> transfers =
        rows("SELECT reference FROM sim_transfers WHERE batch_id=?", id);
    return map(
        "batch_id",
        id,
        "net_minor",
        b.get("net_minor"),
        "amount_minor",
        b.get("net_minor"),
        "cutoff",
        Objects.toString(b.get("cutoff_identity"), ""),
        "occurred_at",
        CanonicalJson.instant(instant(b.get("closed_at"))),
        "transfer_reference",
        transfers.isEmpty() ? "" : transfers.getFirst().get("reference"),
        "state",
        "SUCCEEDED",
        "business_state",
        "COMPLETED",
        "sources",
        rows("SELECT * FROM sim_sources WHERE batch_id=? ORDER BY sequence", id).stream()
            .map(this::sourceDto)
            .toList(),
        "batch_zero",
        ((Number) b.get("net_minor")).longValue() == 0,
        "details",
        rows(
                "SELECT * FROM sim_sources WHERE batch_id=? AND clearing_line=true ORDER BY"
                    + " sequence",
                id)
            .stream()
            .map(this::sourceDto)
            .toList());
  }

  private Map<String, Object> disputeSnapshot(UUID id) {
    Map<String, Object> d = one("SELECT * FROM sim_disputes WHERE id=?", id);
    Map<String, Object> dto =
        map(
            "id",
            id,
            "payment_id",
            d.get("payment_id"),
            "amount_minor",
            d.get("principal_minor"),
            "status",
            d.get("state"),
            "version",
            d.get("version"),
            "response_deadline",
            CanonicalJson.instant(instant(d.get("deadline"))));
    return map(
        "dispute",
        dto,
        "dispute_id",
        id,
        "payment_id",
        d.get("payment_id"),
        "principal_minor",
        d.get("principal_minor"),
        "business_state",
        d.get("state"),
        "version",
        d.get("version"),
        "deadline",
        CanonicalJson.instant(instant(d.get("deadline"))),
        "sources",
        rows(
                "SELECT * FROM sim_sources WHERE resource_id=? OR payment_id=? ORDER BY sequence",
                id,
                d.get("payment_id"))
            .stream()
            .map(this::sourceDto)
            .toList());
  }

  private void resolveDispute(Map<String, Object> d, String outcome, Map<String, Object> c) {
    if ("WON".equals(outcome))
      source(
          c,
          (UUID) d.get("account_id"),
          (UUID) d.get("id"),
          (UUID) d.get("payment_id"),
          "DISPUTE_WON",
          ((Number) d.get("principal_minor")).longValue(),
          batch((UUID) d.get("account_id")),
          true,
          "");
    db.update("UPDATE sim_disputes SET state=?,version=version+1 WHERE id=?", outcome, d.get("id"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> boundedResult(Map<String, Object> original) {
    List<Map<String, Object>> sources =
        (List<Map<String, Object>>) original.getOrDefault("sources", List.of());
    if (sources.size() <= 10000 && CanonicalJson.bytes(original).length <= 128 * 1024)
      return original;
    UUID resource = uuid(original.getOrDefault("batch_id", original.get("resource_id")));
    long high = count("SELECT source_sequence FROM sim_control WHERE id=1");
    long sourceCount =
        count(
            "SELECT count(*) FROM sim_sources WHERE (resource_id=? OR payment_id=? OR batch_id=?)"
                + " AND sequence<=?",
            resource,
            resource,
            resource,
            high);
    Map<String, Object> summary = new LinkedHashMap<>(original);
    summary.remove("details");
    summary.put("sources", List.of());
    Object batches = summary.get("batches");
    if (batches instanceof List<?> list) {
      List<Map<String, Object>> metadata = new ArrayList<>();
      for (Object raw : list) {
        Map<String, Object> batch = new LinkedHashMap<>((Map<String, Object>) raw);
        batch.remove("sources");
        batch.remove("details");
        metadata.add(batch);
      }
      summary.put("batches", metadata);
    }
    summary.put("history_required", true);
    summary.put("history_resource_id", resource);
    summary.put("source_high_water", high);
    summary.put("source_count", sourceCount);
    summary.put("history_source_count", sourceCount);
    if (CanonicalJson.bytes(summary).length > 128 * 1024)
      throw new ProviderException(
          503, "summary_bound", "Provider result metadata exceeds its bound");
    return summary;
  }

  private void emit(Map<String, Object> c, UUID account, String type, Map<String, Object> result) {
    result = boundedResult(result);
    long sequence =
        ((Number)
                one("UPDATE sim_control SET event_sequence=event_sequence+1 WHERE id=1 RETURNING"
                        + " event_sequence")
                    .get("event_sequence"))
            .longValue();
    UUID event = UUID.randomUUID();
    Map<String, Object> body =
        map(
            "event_id",
            event,
            "sequence",
            sequence,
            "schema_version",
            1,
            "policy_version",
            "mprp_v1",
            "environment_id",
            environment,
            "simulator_instance_id",
            c.get("instance_id"),
            "provider_account_id",
            account,
            "event_type",
            type,
            "occurred_at",
            CanonicalJson.instant(Instant.now()),
            "result",
            result);
    String encoded = CanonicalJson.string(body);
    db.update(
        "INSERT INTO sim_events(sequence,event_id,account_id,body) VALUES(?,?,?,?::jsonb)",
        sequence,
        event,
        account,
        encoded);
    byte[] raw = encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (raw.length > 1024 * 1024)
      eventFailure(event, sequence, account, "EVENT_EXCEEDS_PROTOCOL_LIMIT", raw);
  }

  @Transactional
  public Map<String, Object> resolve(Map<String, Object> data) {
    Map<String, Object> c = control();
    envelope(data, c, false);
    String key = text(data, "operation_key");
    if (!key.matches("[A-Za-z0-9_-]{32,128}")) throw ProviderException.invalid("Invalid key");
    List<Map<String, Object>> old = rows("SELECT * FROM sim_operations WHERE operation_key=?", key);
    if (!old.isEmpty()) {
      Map<String, Object> o = old.getFirst();
      String supplied = (String) data.get("payload_hash");
      if (supplied != null
          && !supplied.equals(o.get("payload_hash"))
          && !"NO_EFFECT".equals(o.get("state")))
        throw new ProviderException(409, "key_conflict", "Resolver fingerprint differs");
      return CanonicalJson.parse(o.get("result").toString());
    }
    UUID account = uuid(data.getOrDefault("provider_account_id", data.get("merchant_id")));
    UUID resource =
        data.get("resource_id") == null ? UUID.randomUUID() : uuid(data.get("resource_id"));
    String hash = Objects.toString(data.get("payload_hash"), "");
    Map<String, Object> r =
        result(c, key, hash, account, resource, "NO_EFFECT", "NO_EFFECT", map("tombstone", true));
    db.update(
        "INSERT INTO"
            + " sim_operations(operation_key,payload_hash,kind,account_id,resource_id,state,payload,result)"
            + " VALUES(?,?,'resolver',?,?,'NO_EFFECT','{}'::jsonb,?::jsonb)",
        key,
        hash,
        account,
        resource,
        CanonicalJson.string(r));
    return r;
  }

  public Map<String, Object> operation(String key, UUID account) {
    Map<String, Object> o = one("SELECT * FROM sim_operations WHERE operation_key=?", key);
    if (account != null && !account.equals(o.get("account_id")))
      throw new ProviderException(404, "not_found", "Operation not found");
    return CanonicalJson.parse(o.get("result").toString());
  }

  public Map<String, Object> snapshot(UUID resource) {
    List<Map<String, Object>> payments = rows("SELECT * FROM sim_payments WHERE id=?", resource);
    if (!payments.isEmpty()) return paymentSnapshot(payments.getFirst());
    if (count("SELECT count(*) FROM sim_disputes WHERE id=?", resource) > 0)
      return disputeSnapshot(resource);
    if (count("SELECT count(*) FROM sim_batches WHERE id=?", resource) > 0)
      return boundedResult(batchSnapshot(resource));
    throw new ProviderException(404, "not_found", "Object not found");
  }

  public Map<String, Object> history(UUID resource, long after, Long highWater) {
    long current = count("SELECT source_sequence FROM sim_control WHERE id=1");
    long high = highWater == null ? current : highWater;
    if (high < 0 || high > current || after < 0 || after > high)
      throw new ProviderException(
          409,
          "history_cursor_mismatch",
          "History cursor is outside the committed provider snapshot");
    List<Map<String, Object>> sources =
        rows(
            "SELECT * FROM sim_sources WHERE (resource_id=? OR payment_id=? OR batch_id=?) AND"
                + " sequence>? AND sequence<=? ORDER BY sequence LIMIT 250",
            resource,
            resource,
            resource,
            after,
            high);
    long next =
        sources.isEmpty() ? after : ((Number) sources.getLast().get("sequence")).longValue();
    return map(
        "schema_version",
        1,
        "policy_version",
        "mprp_v1",
        "snapshot",
        high,
        "source_high_water",
        high,
        "after",
        after,
        "next_after",
        next,
        "complete",
        sources.size() < 250,
        "sources",
        sources.stream().map(this::sourceDto).toList());
  }

  private void eventFailure(UUID event, long sequence, UUID account, String code, byte[] raw) {
    db.update(
        "INSERT INTO sim_event_failure(event_id,sequence,account_id,code,body_bytes,digest)"
            + " VALUES(?,?,?,?,?,?) ON CONFLICT(event_id) DO NOTHING",
        event,
        sequence,
        account,
        code,
        raw.length,
        CanonicalJson.digest(raw));
  }

  @Transactional
  public Map<String, Object> events(long after) {
    long high = count("SELECT event_sequence FROM sim_control WHERE id=1");
    if (after < 0 || after > high)
      throw new ProviderException(
          409, "event_cursor_mismatch", "Event cursor is outside committed provider history");
    final int bound = 900 * 1024, reserve = 1024;
    List<Map<String, Object>> selected = new ArrayList<>();
    long[] next = {after};
    int[] bytes = {0};
    boolean[] more = {false};
    Map<String, Object> blocked = new LinkedHashMap<>();
    db.query(
        "SELECT sequence,event_id,account_id,body FROM sim_events WHERE sequence>? ORDER BY"
            + " sequence LIMIT 250",
        rs -> {
          while (rs.next()) {
            long sequence = rs.getLong("sequence");
            UUID id = rs.getObject("event_id", UUID.class),
                account = rs.getObject("account_id", UUID.class);
            byte[] raw = rs.getString("body").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> event;
            try {
              if (raw.length > 1024 * 1024) throw new IllegalArgumentException("oversized");
              event = CanonicalJson.parse(raw);
              raw = CanonicalJson.bytes(event);
            } catch (IllegalArgumentException malformed) {
              String code =
                  raw.length > 1024 * 1024
                      ? "EVENT_EXCEEDS_PROTOCOL_LIMIT"
                      : "INVALID_EVENT_ENCODING";
              eventFailure(id, sequence, account, code, raw);
              more[0] = true;
              blocked.putAll(
                  map(
                      "blocked",
                      true,
                      "blocked_event_id",
                      id,
                      "blocked_sequence",
                      sequence,
                      "safe_reason",
                      code));
              break;
            }
            if (raw.length > bound - reserve) {
              eventFailure(id, sequence, account, "FEED_EVENT_TOO_LARGE", raw);
              more[0] = true;
              blocked.putAll(
                  map(
                      "blocked",
                      true,
                      "blocked_event_id",
                      id,
                      "blocked_sequence",
                      sequence,
                      "safe_reason",
                      "FEED_EVENT_TOO_LARGE"));
              break;
            }
            if (bytes[0] + raw.length + 1 > bound - reserve) {
              more[0] = true;
              break;
            }
            selected.add(event);
            bytes[0] += raw.length + 1;
            next[0] = sequence;
          }
          return null;
        },
        after);
    Map<String, Object> response =
        map(
            "schema_version",
            1,
            "environment_id",
            environment,
            "events",
            selected,
            "next_after",
            next[0],
            "has_more",
            more[0] || next[0] < high);
    response.putAll(blocked);
    if (CanonicalJson.bytes(response).length > bound)
      throw new IllegalStateException("Event response exceeded its bound");
    return response;
  }

  @Transactional
  public Map<String, Object> acknowledge(Map<String, Object> data) {
    Map<String, Object> c = control();
    envelope(data, c, true);
    long cursor =
        number(
            data,
            data.containsKey("cursor") ? "cursor" : "sequence",
            0,
            ((Number) c.get("event_sequence")).longValue());
    db.update(
        "UPDATE sim_control SET acknowledged_cursor=GREATEST(acknowledged_cursor,?) WHERE id=1",
        cursor);
    db.update(
        "UPDATE sim_event_failure SET state='ACKNOWLEDGED',resolved_at=now() WHERE sequence<=? AND"
            + " state='OPEN'",
        cursor);
    return map("acknowledged_cursor", cursor);
  }

  public Map<String, Object> inventory(boolean sources, long after, Long highWater) {
    long current =
        sources
            ? count("SELECT source_sequence FROM sim_control WHERE id=1")
            : count("SELECT COALESCE(max(sequence),0) FROM sim_operations");
    long high = highWater == null ? current : highWater;
    if (high < 0 || high > current || after < 0 || after > high)
      throw new ProviderException(
          409,
          "inventory_cursor_mismatch",
          "Inventory cursor is outside committed provider history");
    String table = sources ? "sim_sources" : "sim_operations";
    List<Map<String, Object>> records =
        rows(
            "SELECT * FROM "
                + table
                + " WHERE sequence>? AND sequence<=? ORDER BY sequence LIMIT 250",
            after,
            high);
    Map<String, Object> result =
        map(
            "snapshot",
            high,
            "source_high_water",
            count("SELECT source_sequence FROM sim_control WHERE id=1"),
            sources ? "sources" : "operations",
            records.stream()
                .map(r -> sources ? sourceDto(r) : CanonicalJson.parse(r.get("result").toString()))
                .toList(),
            "next_after",
            records.isEmpty() ? after : records.getLast().get("sequence"),
            "complete",
            records.size() < 250);
    return result;
  }

  @Transactional
  public Map<String, Object> inventorySummary(UUID account) {
    Map<String, Object> c = control();
    long sourceHigh = ((Number) c.get("source_sequence")).longValue(),
        operationHigh = count("SELECT COALESCE(max(sequence),0) FROM sim_operations");
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      digest.update(
          CanonicalJson.bytes(
              map(
                  "environment_id",
                  environment,
                  "simulator_instance_id",
                  c.get("instance_id"),
                  "account_id",
                  account,
                  "source_high_water",
                  sourceHigh,
                  "operation_high_water",
                  operationHigh,
                  "event_high_water",
                  c.get("event_sequence"))));
      long sourceCount = 0, operationCount = 0;
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(4).toNanos();
      for (String table : List.of("sim_operations", "sim_sources")) {
        long cursor = 0;
        long high = table.equals("sim_sources") ? sourceHigh : operationHigh;
        for (int pages = 0; pages < 10000; pages++) {
          if (System.nanoTime() > deadline)
            throw new ProviderException(
                503,
                "inventory_deadline",
                "Inventory exceeds bounded synchronous inspection; retain hold");
          List<Map<String, Object>> part =
              account == null
                  ? rows(
                      "SELECT * FROM "
                          + table
                          + " WHERE sequence>? AND sequence<=? ORDER BY sequence LIMIT 250",
                      cursor,
                      high)
                  : rows(
                      "SELECT * FROM "
                          + table
                          + " WHERE account_id=? AND sequence>? AND sequence<=? ORDER BY sequence"
                          + " LIMIT 250",
                      account,
                      cursor,
                      high);
          for (Map<String, Object> r : part) {
            digest.update((byte) '\n');
            digest.update(
                CanonicalJson.bytes(
                    table.equals("sim_sources")
                        ? sourceDto(r)
                        : map(
                            "sequence",
                            r.get("sequence"),
                            "operation_key",
                            r.get("operation_key"),
                            "payload_hash",
                            r.get("payload_hash"),
                            "state",
                            r.get("state"),
                            "account_id",
                            r.get("account_id"),
                            "resource_id",
                            r.get("resource_id"))));
          }
          if (table.equals("sim_sources")) sourceCount += part.size();
          else operationCount += part.size();
          if (part.size() < 250) break;
          cursor = ((Number) part.getLast().get("sequence")).longValue();
          if (pages == 9999)
            throw new ProviderException(
                503, "inventory_bound", "Provider inventory exceeds inspection bound; retain hold");
        }
      }
      String hash = java.util.HexFormat.of().formatHex(digest.digest());
      return map(
          "inventory_hash",
          hash,
          "provider_inventory_hash",
          hash,
          "provider_account_id",
          account,
          "source_high_water",
          sourceHigh,
          "operation_high_water",
          operationHigh,
          "event_high_water",
          c.get("event_sequence"),
          "source_count",
          sourceCount,
          "operation_count",
          operationCount,
          "environment_id",
          environment,
          "simulator_instance_id",
          c.get("instance_id"));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public void authorizePurgeSecret(String secret) {
    Object expected =
        one("SELECT purge_secret_digest FROM sim_control WHERE id=1").get("purge_secret_digest");
    if (secret == null
        || secret.length() < 32
        || secret.length() > 256
        || expected == null
        || !java.security.MessageDigest.isEqual(
            CanonicalJson.digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            expected.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
      throw new ProviderException(
          403,
          "purge_authority_required",
          "Separate operator authority is required for namespace retirement");
  }

  @Transactional
  public Map<String, Object> retire(UUID id, Map<String, Object> data) {
    Map<String, Object> c = control();
    envelope(data, c, true);
    if (!id.equals(uuid(data.get("provider_account_id"))))
      throw ProviderException.invalid("Retirement path and provider account differ");
    db.update(
        "INSERT INTO sim_accounts(id,merchant_id,environment_id) VALUES(?,?,?) ON CONFLICT DO"
            + " NOTHING",
        id,
        id,
        environment);
    Map<String, Object> a = one("SELECT * FROM sim_accounts WHERE id=? FOR UPDATE", id);
    List<Map<String, Object>> old =
        rows("SELECT * FROM sim_retired_namespaces WHERE account_id=?", id);
    UUID approval = uuid(data.get("approval_id"));
    String hash = text(data, "inventory_hash");
    if (!old.isEmpty()) {
      if (!approval.equals(old.getFirst().get("approval_id"))
          || !hash.equals(old.getFirst().get("inventory_hash")))
        throw new ProviderException(409, "retirement_conflict", "Retirement is immutable");
      return retirement(id);
    }
    Instant closed = Instant.parse(text(data, "merchant_closed_at"));
    if (closed.isAfter(Instant.now().minus(Duration.ofDays(365))))
      throw new ProviderException(
          409, "retention_active", "365-day closed-merchant retention remains active");
    if (count("SELECT COALESCE(max(sequence),0) FROM sim_events WHERE account_id=?", id)
        > ((Number) c.get("acknowledged_cursor")).longValue())
      throw new ProviderException(
          409,
          "events_not_acknowledged",
          "Main must durably acknowledge account event delivery before retirement");
    if (((Number) a.get("bank_balance_minor")).longValue() != 0
        || count(
                "SELECT count(*) FROM sim_disputes WHERE account_id=? AND state"
                    + " IN('OPEN','RESPONDED')",
                id)
            > 0
        || count(
                "SELECT count(*) FROM sim_payments WHERE account_id=? AND state='AUTHORIZED' AND"
                    + " authorization_expires_at>now()",
                id)
            > 0)
      throw new ProviderException(
          409, "retirement_blocked", "Unresolved provider activity remains");
    if (!hash.equals(inventorySummary(id).get("inventory_hash")))
      throw new ProviderException(409, "inventory_changed", "Retirement inventory differs");
    db.update(
        "INSERT INTO"
            + " sim_retired_namespaces(account_id,merchant_id,retirement_id,inventory_hash,approval_id,merchant_closed_at,phase)"
            + " VALUES(?,?,?,?,?,?,1)",
        id,
        a.get("merchant_id"),
        UUID.randomUUID(),
        hash,
        approval,
        Timestamp.from(closed));
    db.update("UPDATE sim_accounts SET retired=true WHERE id=?", id);
    return retirement(id);
  }

  public Map<String, Object> retirement(UUID id) {
    Map<String, Object> r = one("SELECT * FROM sim_retired_namespaces WHERE account_id=?", id);
    return map(
        "account_id",
        id,
        "retirement_id",
        r.get("retirement_id"),
        "inventory_hash",
        r.get("inventory_hash"),
        "phase",
        r.get("phase"),
        "retired",
        true,
        "completed",
        r.get("completed_at") != null);
  }

  @Transactional
  public Map<String, Object> release(Map<String, Object> data) {
    Map<String, Object> c = control();
    envelope(data, c, true);
    long cursor =
        number(data, "acknowledged_cursor", 0, ((Number) c.get("event_sequence")).longValue());
    if (cursor != ((Number) c.get("event_sequence")).longValue())
      throw new ProviderException(409, "inventory_incomplete", "All events must be reconciled");
    if (!Objects.equals(data.get("inventory_hash"), inventorySummary(null).get("inventory_hash")))
      throw new ProviderException(409, "inventory_changed", "Provider source inventory differs");
    db.update(
        "UPDATE sim_control SET restored=false,acknowledged_cursor=GREATEST(acknowledged_cursor,?)"
            + " WHERE id=1",
        cursor);
    db.update(
        "UPDATE sim_event_failure SET state='ACKNOWLEDGED',resolved_at=now() WHERE sequence<=? AND"
            + " state='OPEN'",
        cursor);
    return capabilities();
  }

  @Transactional
  public void heartbeat() {
    db.update("UPDATE sim_control SET worker_heartbeat=now() WHERE id=1");
  }

  @Transactional
  public void progress() {
    Map<String, Object> c = control();
    db.update("UPDATE sim_control SET worker_heartbeat=now() WHERE id=1");
    if (Boolean.TRUE.equals(c.get("restored"))) return;
    for (Map<String, Object> d :
        rows(
            "SELECT * FROM sim_disputes WHERE state IN('OPEN','RESPONDED') AND deadline<=now()"
                + " ORDER BY id LIMIT 100 FOR UPDATE")) {
      String outcome =
          "RESPONDED".equals(d.get("state")) ? (String) d.get("selected_outcome") : "LOST";
      resolveDispute(d, outcome, c);
      Map<String, Object> result =
          result(
              c,
              "system-dispute-" + d.get("id"),
              "",
              (UUID) d.get("account_id"),
              (UUID) d.get("id"),
              "SUCCEEDED",
              outcome,
              disputeSnapshot((UUID) d.get("id")));
      emit(c, (UUID) d.get("account_id"), "dispute_outcome", result);
    }
    for (Map<String, Object> pay :
        rows(
            "SELECT * FROM sim_payments WHERE state='AUTHORIZED' AND"
                + " authorization_expires_at<=now() ORDER BY id LIMIT 100 FOR UPDATE")) {
      UUID payment = (UUID) pay.get("id"), account = (UUID) pay.get("account_id");
      db.update("UPDATE sim_payments SET state='EXPIRED',version=version+1 WHERE id=?", payment);
      List<Map<String, Object>> original =
          rows(
              "SELECT * FROM sim_operations WHERE account_id=? AND resource_id=? AND"
                  + " kind='authorize' AND state='SUCCEEDED' ORDER BY sequence LIMIT 1",
              account,
              payment);
      if (!original.isEmpty()) {
        Map<String, Object> op = original.getFirst();
        Map<String, Object> result =
            result(
                c,
                op.get("operation_key").toString(),
                op.get("payload_hash").toString(),
                account,
                payment,
                "SUCCEEDED",
                "EXPIRED",
                paymentSnapshot(payment(account, payment)));
        emit(c, account, "authorization_expired", result);
      }
    }
  }
}
