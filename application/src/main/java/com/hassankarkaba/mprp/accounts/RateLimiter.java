package com.hassankarkaba.mprp.accounts;

import com.hassankarkaba.mprp.operations.Db;
import com.hassankarkaba.mprp.operations.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A shared sliding window. Dedicated transactions retain abuse evidence after rejected requests.
 */
@Service
public class RateLimiter {
  private final Db db;

  public RateLimiter(Db db) {
    this.db = db;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void admit(String bucket, int limit, boolean record) {
    db.query("select pg_advisory_xact_lock(hashtextextended(?,0))", bucket);
    long count =
        db.scalar(
            "select count(*) from rate_event where bucket=? and"
                + " occurred_at>clock_timestamp()-interval '15 minutes'",
            Long.class,
            bucket);
    if (count >= limit)
      throw new DomainException(429, "Too many attempts. Try again after the 15-minute window.");
    if (record) db.exec("insert into rate_event(bucket) values (?)", bucket);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failure(String bucket) {
    db.exec("insert into rate_event(bucket) values (?)", bucket);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public java.util.Optional<java.util.Map<String, Object>> authenticate(
      String email,
      String password,
      String source,
      org.springframework.security.crypto.password.PasswordEncoder encoder) {
    String account = "auth-account:" + SecretCodec.digest(email), origin = "auth-source:" + source;
    java.util.stream.Stream.of(account, origin)
        .sorted()
        .forEach(bucket -> db.query("select pg_advisory_xact_lock(hashtextextended(?,0))", bucket));
    for (var bound : java.util.Map.of(account, 5, origin, 30).entrySet())
      if (db.scalar(
              "select count(*) from rate_event where bucket=? and"
                  + " occurred_at>clock_timestamp()-interval '15 minutes'",
              Long.class,
              bound.getKey())
          >= bound.getValue())
        throw new DomainException(429, "Too many attempts. Try again after the 15-minute window.");
    var user = db.maybe("select * from app_user where email=? and active for update", email);
    if (user.isPresent()
        && user.get().get("password_hash") instanceof String hash
        && password != null
        && password.codePointCount(0, password.length()) <= 128
        && encoder.matches(password, hash)) return user;
    db.exec("insert into rate_event(bucket) values (?),(?)", account, origin);
    return java.util.Optional.empty();
  }
}
