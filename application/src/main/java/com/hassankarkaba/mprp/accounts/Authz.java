package com.hassankarkaba.mprp.accounts;

import com.hassankarkaba.mprp.operations.Db;
import com.hassankarkaba.mprp.operations.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class Authz {
  private final Db db;

  public Authz(Db db) {
    this.db = db;
  }

  public static HttpServletRequest request() {
    var a = RequestContextHolder.getRequestAttributes();
    if (!(a instanceof ServletRequestAttributes s))
      throw new DomainException(401, "Sign in is required");
    return s.getRequest();
  }

  /**
   * Identity only for acquiring stable locks; callers must subsequently call
   * current/require/platform.
   */
  public UUID actorForLocking() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated())
      throw new DomainException(401, "Sign in is required");
    try {
      return UUID.fromString(authentication.getName());
    } catch (Exception e) {
      throw new DomainException(401, "Sign in is required");
    }
  }

  public Map<String, Object> identity() {
    UUID id = actorForLocking();
    boolean transactional =
        org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive();
    var user =
        db.one("select * from app_user where id=?" + (transactional ? " for update" : ""), id);
    HttpSession session = request().getSession(false);
    long epoch = ((Number) user.get("auth_epoch")).longValue();
    if (session == null
        || !Boolean.TRUE.equals(user.get("active"))
        || !Long.valueOf(epoch).equals(session.getAttribute("epoch"))
        || !(session.getAttribute("login_at") instanceof Instant born)
        || !Instant.now().isBefore(born.plusSeconds(43200))) {
      if (session != null) session.invalidate();
      SecurityContextHolder.clearContext();
      throw new DomainException(401, "Your session expired. Sign in again.");
    }
    return user;
  }

  public UUID current() {
    var user = identity();
    if (!Boolean.TRUE.equals(user.get("verified"))
        || !Boolean.TRUE.equals(user.get("totp_confirmed"))
        || !Boolean.TRUE.equals(request().getSession().getAttribute("mfa")))
      throw new DomainException(403, "Complete email verification and MFA first.");
    return Db.uuid(user, "id");
  }

  public UUID require(UUID merchant, String... roles) {
    UUID actor = current();
    boolean transactional =
        org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive();
    if (transactional) db.lockMerchant(merchant);
    var member =
        db.maybe(
                "select role from membership where user_id=? and merchant_id=? and active"
                    + (transactional ? " for update" : ""),
                actor,
                merchant)
            .orElseThrow(() -> new DomainException(404, "Merchant resource not found"));
    if (roles.length > 0 && !Arrays.asList(roles).contains(member.get("role").toString()))
      throw new DomainException(403, "Your merchant role does not permit this action.");
    return actor;
  }

  public UUID platform(String... roles) {
    UUID actor = current();
    var user = db.one("select platform_role from app_user where id=?", actor);
    if (user.get("platform_role") == null
        || !Arrays.asList(roles).contains(user.get("platform_role").toString()))
      throw new DomainException(403, "Your platform role does not permit this action.");
    return actor;
  }

  public void stepUp() {
    current();
    Object value = request().getSession().getAttribute("step_up_at");
    if (!(value instanceof Instant at) || !Instant.now().isBefore(at.plusSeconds(300)))
      throw new DomainException(
          403, "A fresh password and authenticator step-up is required at /accounts/step-up/.");
  }
}
