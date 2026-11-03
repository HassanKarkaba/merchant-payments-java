package com.hassankarkaba.mprp.accounts;

import com.hassankarkaba.mprp.operations.AuditService;
import com.hassankarkaba.mprp.operations.Db;
import com.hassankarkaba.mprp.operations.DomainException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  private final Db db;
  private final SecretCodec secrets;
  private final PasswordEncoder passwords;
  private final RateLimiter rates;
  private final String baseUrl;
  private final AuditService audit;
  private final Authz authz;

  public AccountService(
      Db db,
      SecretCodec secrets,
      PasswordEncoder passwords,
      RateLimiter rates,
      AuditService audit,
      Authz authz,
      @Value("${mprp.public-url:http://localhost:8180}") String baseUrl) {
    this.db = db;
    this.secrets = secrets;
    this.passwords = passwords;
    this.rates = rates;
    this.audit = audit;
    this.authz = authz;
    this.baseUrl = baseUrl;
    if (!baseUrl.matches("http://(localhost|127\\.0\\.0\\.1):[0-9]{1,5}"))
      throw new IllegalStateException("public-url must be loopback HTTP");
  }

  @Transactional
  public void register(Map<String, String> form, String source) {
    AccountRules.fields(form.keySet(), "email", "password", "merchant_name", "timezone");
    String email = AccountRules.email(form.get("email")),
        password = AccountRules.password(form.get("password"), email),
        name = AccountRules.business(form.get("merchant_name")),
        zone = AccountRules.timezone(form.get("timezone"));
    rates.admit("registration:" + source, 10, true);
    if (db.maybe("select id from app_user where email=?", email).isPresent()) return;
    UUID user = UUID.randomUUID(), merchant = UUID.randomUUID();
    if (db.exec(
            "insert into app_user(id,email,password_hash) values (?,?,?) on conflict(email) do"
                + " nothing",
            user,
            email,
            passwords.encode(password))
        != 1) return;
    db.exec(
        "insert into merchant(id,name,timezone,provider_account_id,bank_account_id) values"
            + " (?,?,?,?,?)",
        merchant,
        name,
        zone,
        merchant,
        merchant);
    db.exec(
        "insert into membership(id,user_id,merchant_id,role) values (?,?,?,'OWNER')",
        UUID.randomUUID(),
        user,
        merchant);
    replaceToken(user, "VERIFY", email, 86400);
    event(user, merchant, "REGISTERED");
  }

  public Map<String, Object> login(String rawEmail, String password, String source) {
    String email;
    try {
      email = AccountRules.email(rawEmail);
    } catch (Exception invalid) {
      email = "invalid@demo.invalid";
    }
    var found = rates.authenticate(email, password, source, passwords);
    if (found.isEmpty()) throw new DomainException(400, "Email or password is incorrect.");
    return found.get();
  }

  @Transactional
  public void resend(UUID user) {
    identity(user, false);
    var u = db.one("select * from app_user where id=? for update", user);
    rates.admit("resend:" + user, 3, true);
    if (!Boolean.TRUE.equals(u.get("verified")))
      replaceToken(user, "VERIFY", u.get("email").toString(), 86400);
  }

  @Transactional
  public void requestReset(String rawEmail, String source) {
    rates.admit("reset:" + source, 10, true);
    String email;
    try {
      email = AccountRules.email(rawEmail);
    } catch (Exception ignored) {
      return;
    }
    db.maybe("select * from app_user where email=? and active for update", email)
        .ifPresent(u -> replaceToken(Db.uuid(u, "id"), "RESET", email, 1800));
  }

  @Transactional
  public void verify(String token) {
    var t = lockToken(token, "VERIFY");
    UUID user = Db.uuid(t, "user_id");
    db.exec("update app_user set verified=true where id=?", user);
    consume(t);
    event(user, null, "EMAIL_VERIFIED");
  }

  @Transactional
  public void resetPassword(String token, String password) {
    var t = lockToken(token, "RESET");
    UUID user = Db.uuid(t, "user_id");
    var u = db.one("select * from app_user where id=? for update", user);
    AccountRules.password(password, u.get("email").toString());
    db.exec(
        "update app_user set password_hash=?,auth_epoch=auth_epoch+1 where id=?",
        passwords.encode(password),
        user);
    consume(t);
    db.exec(
        "update account_token set revoked_at=clock_timestamp() where user_id=? and consumed_at is"
            + " null and revoked_at is null",
        user);
    event(user, null, "PASSWORD_RESET");
  }

  @Transactional
  public String enrollmentSeed(UUID user) {
    identity(user, false);
    var u = db.one("select * from app_user where id=? for update", user);
    if (!Boolean.TRUE.equals(u.get("verified")))
      throw new DomainException(403, "Verify your email before enrolling an authenticator.");
    if (Boolean.TRUE.equals(u.get("totp_confirmed")))
      throw new DomainException(409, "Authenticator is already enrolled.");
    if (u.get("totp_cipher") == null) {
      String seed = SecretCodec.newTotpSeed();
      db.exec(
          "update app_user set totp_cipher=? where id=?",
          secrets.encrypt(seed, "totp:" + user),
          user);
      return seed;
    }
    return secrets.decrypt(u.get("totp_cipher").toString(), "totp:" + user);
  }

  @Transactional
  public List<String> enroll(UUID user, String code) {
    identity(user, false);
    if (Boolean.TRUE.equals(
        db.one("select totp_confirmed from app_user where id=? for update", user)
            .get("totp_confirmed")))
      throw new DomainException(
          409,
          "Authenticator is already enrolled. Replace recovery codes through the step-up protected"
              + " security page.");
    checkTotp(user, code, false);
    db.exec("update app_user set totp_confirmed=true where id=?", user);
    event(user, null, "MFA_ENROLLED");
    return issueRecoveryCodes(user);
  }

  @Transactional
  public long completeMfa(UUID user, String code) {
    identity(user, false);
    var u = db.one("select * from app_user where id=? for update", user);
    if (!Boolean.TRUE.equals(u.get("verified")) || !Boolean.TRUE.equals(u.get("totp_confirmed")))
      throw new DomainException(403, "Verification and authenticator enrollment are required.");
    if (code != null && code.matches("[0-9]{6}")) {
      checkTotp(user, code, true);
      return Db.number(u, "auth_epoch");
    }
    rates.admit("mfa:" + user, 5, false);
    int changed =
        db.exec(
            "update recovery_code set used_at=clock_timestamp() where user_id=? and digest=? and"
                + " used_at is null",
            user,
            SecretCodec.digest(code == null ? "" : code.trim()));
    if (changed != 1) {
      rates.failure("mfa:" + user);
      throw new DomainException(400, "Authenticator or recovery code is invalid or already used.");
    }
    db.exec("update app_user set auth_epoch=auth_epoch+1 where id=?", user);
    event(user, null, "RECOVERY_CODE_USED");
    return Db.number(u, "auth_epoch") + 1;
  }

  @Transactional
  public void sensitiveCheck(UUID user, String password, String code) {
    identity(user, true);
    var u = db.one("select * from app_user where id=? for update", user);
    rates.admit("mfa:" + user, 5, false);
    if (!(u.get("password_hash") instanceof String hash)
        || password == null
        || !passwords.matches(password, hash)) {
      rates.failure("mfa:" + user);
      throw new DomainException(400, "Password or fresh authenticator code is incorrect.");
    }
    checkTotp(user, code, true);
  }

  private void checkTotp(UUID user, String code, boolean confirmed) {
    var u = db.one("select * from app_user where id=? for update", user);
    if (u.get("totp_cipher") == null
        || (confirmed && !Boolean.TRUE.equals(u.get("totp_confirmed"))))
      throw new DomainException(403, "Enroll an authenticator first.");
    rates.admit("mfa:" + user, 5, false);
    String seed = secrets.decrypt(u.get("totp_cipher").toString(), "totp:" + user);
    long current = Instant.now().getEpochSecond() / 30, last = Db.number(u, "totp_last_counter");
    for (long counter = current - 1; counter <= current + 1; counter++) {
      if (counter > last
          && java.security.MessageDigest.isEqual(
              SecretCodec.totp(seed, counter).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
              (code == null ? "" : code).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
        db.exec("update app_user set totp_last_counter=? where id=?", counter, user);
        return;
      }
    }
    rates.failure("mfa:" + user);
    throw new DomainException(
        400, "Authenticator code is invalid or already used. Wait for a fresh code.");
  }

  @Transactional
  public List<String> replaceRecoveryCodes(UUID user) {
    identity(user, true);
    authz.stepUp();
    return issueRecoveryCodes(user);
  }

  List<String> issueRecoveryCodes(UUID user) {
    db.one("select id from app_user where id=? for update", user);
    db.exec("delete from recovery_code where user_id=?", user);
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      String code = SecretCodec.randomToken().substring(0, 22);
      codes.add(code);
      db.exec(
          "insert into recovery_code(user_id,digest) values (?,?)", user, SecretCodec.digest(code));
    }
    event(user, null, "RECOVERY_CODES_REPLACED");
    return codes;
  }

  @Transactional
  public void logoutAll(UUID user) {
    identity(user, true);
    db.one("select id from app_user where id=? for update", user);
    db.exec("update app_user set auth_epoch=auth_epoch+1 where id=?", user);
    event(user, null, "LOGOUT_ALL");
  }

  @Transactional
  public void deactivate(UUID user) {
    identity(user, true);
    authz.stepUp();
    db.one("select id from app_user where id=? for update", user);
    var memberships =
        db.query(
            "select merchant_id from membership where user_id=? and active order by merchant_id",
            user);
    for (var m : memberships) db.lockMerchant(Db.uuid(m, "merchant_id"));
    if (db.scalar(
            "select count(*) from membership me join merchant m on m.id=me.merchant_id where"
                + " me.user_id=? and me.active and me.role='OWNER' and m.status<>'CLOSED' and not"
                + " exists(select 1 from membership x join app_user u on u.id=x.user_id where"
                + " x.merchant_id=m.id and x.user_id<>? and x.active and x.role='OWNER' and"
                + " u.active)",
            Long.class,
            user,
            user)
        > 0)
      throw new DomainException(
          409, "Transfer ownership before deactivating the last active owner.");
    db.exec(
        "update membership set active=false,version=version+1 where user_id=? and active", user);
    db.exec(
        "update account_token set revoked_at=clock_timestamp() where (user_id=? or issuer_id=?) and"
            + " revoked_at is null",
        user,
        user);
    db.exec("delete from recovery_code where user_id=?", user);
    db.exec(
        "update app_user set"
            + " active=false,email=?,password_hash=null,totp_cipher=null,totp_confirmed=false,auth_epoch=auth_epoch+1"
            + " where id=?",
        "retired-" + user + "@demo.invalid",
        user);
    event(user, null, "ACCOUNT_DEACTIVATED");
  }

  private void identity(UUID user, boolean full) {
    UUID actor = full ? authz.current() : Db.uuid(authz.identity(), "id");
    if (!actor.equals(user)) throw new DomainException(403, "Account authority mismatch.");
  }

  private Map<String, Object> lockToken(String token, String kind) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw new DomainException(400, "This link is invalid or expired.");
    var peek =
        db.maybe(
                "select * from account_token where token_digest=? and kind=?",
                SecretCodec.digest(token),
                kind)
            .orElseThrow(() -> new DomainException(400, "This link is invalid or expired."));
    if (peek.get("user_id") != null)
      db.one("select id from app_user where id=? and active for update", peek.get("user_id"));
    var t =
        db.one(
            "select *,expires_at>clock_timestamp() as valid from account_token where id=? for"
                + " update",
            peek.get("id"));
    if (!Boolean.TRUE.equals(t.get("valid"))
        || t.get("consumed_at") != null
        || t.get("revoked_at") != null)
      throw new DomainException(400, "This link is invalid or expired.");
    return t;
  }

  private void consume(Map<String, Object> token) {
    db.exec("update account_token set consumed_at=clock_timestamp() where id=?", token.get("id"));
  }

  private void replaceToken(UUID user, String kind, String email, int seconds) {
    db.exec(
        "update account_token set revoked_at=clock_timestamp() where user_id=? and kind=? and"
            + " consumed_at is null and revoked_at is null",
        user,
        kind);
    String token = SecretCodec.randomToken();
    db.exec(
        "insert into account_token(id,token_digest,user_id,kind,expires_at) values"
            + " (?,?,?,?,clock_timestamp()+(? * interval '1 second'))",
        UUID.randomUUID(),
        SecretCodec.digest(token),
        user,
        kind,
        seconds);
    mail(
        email,
        kind.equals("VERIFY") ? "Verify your fictional account" : "Reset your sandbox password",
        baseUrl + "/accounts/" + (kind.equals("VERIFY") ? "verify/" : "reset/") + token + "/",
        seconds);
  }

  public void mail(String email, String subject, String body, int seconds) {
    UUID id = UUID.randomUUID();
    db.exec(
        "insert into account_mail(id,recipient,subject,encrypted_body,expires_at) values"
            + " (?,?,?,?,clock_timestamp()+(? * interval '1 second'))",
        id,
        email,
        subject,
        secrets.encrypt(body, "mail:" + id),
        seconds);
  }

  public void event(UUID actor, UUID merchant, String action) {
    UUID id = UUID.randomUUID();
    db.exec(
        "insert into account_event(id,actor_id,merchant_id,action) values (?,?,?,?)",
        id,
        actor,
        merchant,
        action);
    audit.record(merchant, actor, action, id, "");
    if (actor != null)
      db.exec(
          "insert into notification(id,user_id,merchant_id,title,body) values (?,?,?,?,?)",
          id,
          actor,
          merchant,
          "Account security update",
          action.replace('_', ' '));
  }

  public PasswordEncoder passwords() {
    return passwords;
  }
}
