package com.hassankarkaba.mprp.settlements;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.operations.*;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

public final class WorkflowSupport {
  private WorkflowSupport() {}

  public static UUID id(Map<String, Object> r, String k) {
    return UUID.fromString(r.get(k).toString());
  }

  public static long n(Map<String, Object> r, String k) {
    return ((Number) r.get(k)).longValue();
  }

  public static String s(Map<String, Object> r, String k) {
    Object v = r.get(k);
    return v == null ? "" : v.toString();
  }

  public static Instant time(Object v) {
    if (v instanceof Timestamp t) return t.toInstant();
    if (v instanceof OffsetDateTime t) return t.toInstant();
    if (v instanceof Instant t) return t;
    return Instant.parse(v.toString());
  }

  public static String text(String value, int max) {
    String s =
        java.text.Normalizer.normalize(
            value == null ? "" : value.trim(), java.text.Normalizer.Form.NFC);
    require(
        !s.isEmpty() && s.codePointCount(0, s.length()) <= max,
        400,
        "Enter between 1 and " + max + " characters");
    return s;
  }

  public static void version(Map<String, Object> row, long expected) {
    require(
        n(row, "version") == expected,
        409,
        "This record changed. Reload and inspect before acting.");
  }

  public static void action(String key) {
    require(key != null && key.matches("[A-Za-z0-9_-]{32,128}"), 400, "Invalid action key");
  }

  public static String key() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  public static Map<String, Object> map(Object... args) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i += 2) m.put(args[i].toString(), args[i + 1]);
    return m;
  }

  public static Optional<UUID> receipt(
      Db db, UUID merchant, UUID actor, String kind, UUID resource, String key, Object payload) {
    action(key);
    String hash = CanonicalJson.sha256(payload);
    var old =
        db.maybe(
            "select * from workflow_receipt where merchant_id=? and actor=? and kind=? and"
                + " resource_id=? and action_key=?",
            merchant,
            actor,
            kind,
            resource,
            key);
    if (old.isPresent()) {
      require(
          hash.equals(s(old.get(), "fingerprint")),
          409,
          "Action key was already used with different input");
      return Optional.of(id(old.get(), "result_id"));
    }
    return Optional.empty();
  }

  public static void saveReceipt(
      Db db,
      UUID merchant,
      UUID actor,
      String kind,
      UUID resource,
      String key,
      Object payload,
      UUID result) {
    db.exec(
        "insert into"
            + " workflow_receipt(id,merchant_id,actor,kind,resource_id,action_key,fingerprint,result_id)"
            + " values(?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        merchant,
        actor,
        kind,
        resource,
        key,
        CanonicalJson.sha256(payload),
        result);
  }

  public static void activeMerchant(Db db, UUID merchant) {
    require(
        "ACTIVE".equals(s(db.one("select status from merchant where id=?", merchant), "status")),
        409,
        "Merchant must be active for new financial work");
  }

  public static void member(Db db, UUID user, UUID merchant, String... roles) {
    require(
        db.scalar(
                "select count(*) from app_user u join membership m on m.user_id=u.id where u.id=?"
                    + " and u.active and u.verified and u.totp_confirmed and m.merchant_id=? and"
                    + " m.active and m.role in ("
                    + String.join(",", Collections.nCopies(roles.length, "?"))
                    + ")",
                Long.class,
                concat(user, merchant, roles))
            > 0,
        403,
        "Maker or checker no longer has current authority");
  }

  private static Object[] concat(UUID user, UUID merchant, String[] roles) {
    Object[] a = new Object[roles.length + 2];
    a[0] = user;
    a[1] = merchant;
    System.arraycopy(roles, 0, a, 2, roles.length);
    return a;
  }

  public static void platform(Db db, UUID user, String role) {
    require(
        db.scalar(
                "select count(*) from app_user where id=? and active and verified and"
                    + " totp_confirmed and platform_role=?",
                Long.class,
                user,
                role)
            > 0,
        403,
        "Maker or checker no longer has current platform authority");
  }

  public static void lockActors(Db db, UUID... users) {
    TreeSet<UUID> sorted = new TreeSet<>(Comparator.comparing(UUID::toString));
    for (UUID user : users) if (user != null) sorted.add(user);
    for (UUID user : sorted) db.one("select id from app_user where id=? for update", user);
  }

  public static String required(Map<String, String> input, String field) {
    String value = input.get(field);
    require(value != null && !value.isBlank(), 400, "Missing required field: " + field);
    return value;
  }

  public static void reconciliationReady(Db db, Readiness readiness) {
    var control = db.one("select clock_at from system_control where id=true for update");
    require(
        !time(control.get("clock_at")).isAfter(Instant.now().plusSeconds(2)),
        503,
        "Clock safety hold");
    require(
        Boolean.TRUE.equals(readiness.capabilities().get("reconciliation")),
        503,
        "Reconciliation capability is held or unready");
  }
}
