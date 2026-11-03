package com.hassankarkaba.mprp.accounts;

import com.hassankarkaba.mprp.operations.AuditService;
import com.hassankarkaba.mprp.operations.Db;
import com.hassankarkaba.mprp.operations.DomainException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit offline owner-credential boundary. This service has no browser or worker handler. */
@Service
public class AccountsLocalAdmin {
  private final Db db;
  private final SecretCodec secrets;
  private final PasswordEncoder encoder;
  private final RateLimiter rates;
  private final AuditService audit;

  public AccountsLocalAdmin(
      Db db, SecretCodec secrets, PasswordEncoder encoder, RateLimiter rates, AuditService audit) {
    this.db = db;
    this.secrets = secrets;
    this.encoder = encoder;
    this.rates = rates;
    this.audit = audit;
  }

  @Transactional
  public void bootstrap(
      String rawEmail,
      String role,
      UUID operator,
      String reason,
      Path newPassword,
      Path operatorPassword,
      Path operatorTotp) {
    ownerBoundary();
    if (operator == null) throw new DomainException(400, "A stable operator UUID is required.");
    String email = AccountRules.email(rawEmail);
    String password = AccountRules.password(readPrivate(newPassword), email);
    reason = reason(reason);
    if (!Set.of("OPERATIONS", "RECONCILIATION", "FINANCE_APPROVER", "AUDITOR").contains(role))
      throw new DomainException(400, "Choose an explicit supported platform role.");
    db.query("select pg_advisory_xact_lock(hashtextextended('mprp_staff_bootstrap',0))");
    if (db.maybe("select id from app_user where email=?", email).isPresent())
      throw new DomainException(
          409, "Account already exists. Refusing to replace its identity or authority.");
    long existing =
        db.scalar(
            "select count(*) from app_user where active and platform_role='OPERATIONS'",
            Long.class);
    if (existing == 0) {
      if (!role.equals("OPERATIONS"))
        throw new DomainException(
            409, "Bootstrap the first OPERATIONS identity before provisioning other staff.");
    } else {
      var actor = db.one("select * from app_user where id=? for update", operator);
      proveOperator(actor, operatorPassword, operatorTotp);
    }
    UUID user = UUID.randomUUID();
    db.exec(
        "insert into app_user(id,email,password_hash,verified,platform_role) values (?,?,?,true,?)",
        user,
        email,
        encoder.encode(password),
        role);
    audit.record(
        null,
        operator,
        "staff.bootstrapped",
        user,
        reason
            + "; role="
            + role
            + "; "
            + (existing == 0
                ? "initial owner-credential bootstrap"
                : "current operations password and TOTP"));
    db.exec(
        "insert into notification(id,user_id,title,body) values (?,?,?,?)",
        UUID.randomUUID(),
        user,
        "Staff account provisioned",
        "Sign in with the privately supplied password and enroll an authenticator before business"
            + " access.");
  }

  @Transactional
  public void recoverMfa(
      String rawEmail,
      UUID operator,
      String reason,
      Path operatorPassword,
      Path operatorTotp,
      boolean ownerRecovery) {
    ownerBoundary();
    if (operator == null) throw new DomainException(400, "A stable operator UUID is required.");
    String email = AccountRules.email(rawEmail);
    reason = reason(reason);
    var initial = db.one("select id from app_user where email=? and active", email);
    UUID target = Db.uuid(initial, "id");
    db.query("select id from app_user where id in (?,?) order by id for update", operator, target);
    var actor = db.one("select * from app_user where id=?", operator);
    var user = db.one("select * from app_user where id=? and active", target);
    if (ownerRecovery) {
      boolean current =
          Boolean.TRUE.equals(actor.get("active"))
              && Boolean.TRUE.equals(actor.get("verified"))
              && "OPERATIONS".equals(actor.get("platform_role"));
      long other =
          db.scalar(
              "select count(*) from app_user where id<>? and active and verified and totp_confirmed"
                  + " and platform_role='OPERATIONS'",
              Long.class,
              operator);
      if (!current || !operator.equals(target) || other != 0)
        throw new DomainException(
            403,
            "Owner recovery is limited to the current sole recoverable OPERATIONS identity."
                + " Otherwise another operations actor must prove password and fresh TOTP.");
    } else proveOperator(actor, operatorPassword, operatorTotp);
    db.exec(
        "update app_user set"
            + " totp_cipher=null,totp_confirmed=false,totp_last_counter=-1,auth_epoch=auth_epoch+1"
            + " where id=?",
        target);
    db.exec("delete from recovery_code where user_id=?", target);
    db.exec(
        "update account_token set revoked_at=clock_timestamp() where (user_id=? or issuer_id=?) and"
            + " consumed_at is null and revoked_at is null",
        target,
        target);
    audit.record(
        null,
        operator,
        "mfa.local_recovery",
        target,
        reason
            + "; "
            + (ownerRecovery
                ? "explicit sole-operations owner-credential recovery"
                : "current operations password and TOTP"));
    db.exec(
        "insert into notification(id,user_id,title,body) values (?,?,?,?)",
        UUID.randomUUID(),
        target,
        "Authenticator recovery performed",
        "Earlier sessions and recovery codes are revoked. Your password and roles are unchanged;"
            + " enroll a new authenticator at sign-in.");
  }

  private void proveOperator(Map<String, Object> actor, Path passwordFile, Path codeFile) {
    UUID operator = Db.uuid(actor, "id");
    String bucket = "admin-auth:" + operator;
    rates.admit(bucket, 5, false);
    if (!Boolean.TRUE.equals(actor.get("active"))
        || !Boolean.TRUE.equals(actor.get("verified"))
        || !Boolean.TRUE.equals(actor.get("totp_confirmed"))
        || !"OPERATIONS".equals(actor.get("platform_role")))
      throw new DomainException(
          403, "A current verified and MFA-ready OPERATIONS actor is required.");
    String password = readPrivate(passwordFile), code = readPrivate(codeFile).strip();
    boolean passwordValid =
        actor.get("password_hash") instanceof String hash && encoder.matches(password, hash);
    if (passwordValid && code.matches("[0-9]{6}") && actor.get("totp_cipher") != null) {
      String seed = secrets.decrypt(actor.get("totp_cipher").toString(), "totp:" + operator);
      long now = Instant.now().getEpochSecond() / 30, last = Db.number(actor, "totp_last_counter");
      for (long counter = now - 1; counter <= now + 1; counter++)
        if (counter > last
            && java.security.MessageDigest.isEqual(
                SecretCodec.totp(seed, counter)
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                code.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
          db.exec("update app_user set totp_last_counter=? where id=?", counter, operator);
          return;
        }
    }
    rates.failure(bucket);
    throw new DomainException(
        403, "Operator password or fresh authenticator code is invalid or already consumed.");
  }

  private void ownerBoundary() {
    if (!"mprp_owner".equals(db.scalar("select current_user", String.class)))
      throw new DomainException(
          403,
          "This command requires the separately supplied local migration-owner credential. It is"
              + " unavailable to web and workers.");
  }

  private static String reason(String value) {
    if (value == null || value.strip().isEmpty() || value.codePointCount(0, value.length()) > 2000)
      throw new DomainException(
          400, "A nonempty operator reason of at most 2000 characters is required.");
    return value.strip();
  }

  private static String readPrivate(Path path) {
    try {
      if (path == null
          || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
          || Files.size(path) > 1024) throw new IllegalArgumentException();
      var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
      for (var permission : permissions)
        if (Set.of(
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE)
            .contains(permission)) throw new IllegalArgumentException();
      String value = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
      if (value.endsWith("\r\n")) return value.substring(0, value.length() - 2);
      if (value.endsWith("\n")) return value.substring(0, value.length() - 1);
      return value;
    } catch (Exception invalid) {
      throw new DomainException(
          400,
          "Use a readable owner-only regular secret file of at most 1024 bytes. Symlinks and shared"
              + " permissions are rejected.");
    }
  }
}
