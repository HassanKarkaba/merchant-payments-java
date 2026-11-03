package com.hassankarkaba.mprp.accounts;

import com.hassankarkaba.mprp.operations.Db;
import com.hassankarkaba.mprp.operations.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class AccountController {
  private final AccountService accounts;
  private final Authz authz;
  private final Db db;
  private final SecretCodec secrets;

  public AccountController(AccountService accounts, Authz authz, Db db, SecretCodec secrets) {
    this.accounts = accounts;
    this.authz = authz;
    this.db = db;
    this.secrets = secrets;
  }

  @GetMapping("/accounts/register/")
  String register(Model model) {
    return form(
        model,
        "Create a fictional merchant",
        "Register",
        Map.of(
            "email", "email", "password", "password", "merchant_name", "text", "timezone", "text"));
  }

  @PostMapping("/accounts/register/")
  Object register(
      @RequestParam Map<String, String> fields, HttpServletRequest request, Model model) {
    try {
      accounts.register(fields, source(request));
      return complete(
          request,
          "Check captured email",
          "If this address is eligible, a verification link has been sent to the local Mailpit"
              + " inbox.");
    } catch (DomainException e) {
      return invalid(
          model,
          e,
          fields,
          "Create a fictional merchant",
          "Register",
          Map.of(
              "email",
              "email",
              "password",
              "password",
              "merchant_name",
              "text",
              "timezone",
              "text"));
    }
  }

  @GetMapping("/accounts/login/")
  String login(Model model) {
    return form(model, "Sign in", "Sign in", Map.of("email", "email", "password", "password"));
  }

  @PostMapping("/accounts/login/")
  Object login(
      @RequestParam Map<String, String> fields,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    AccountRules.fields(fields.keySet(), "email", "password", "next");
    try {
      var user = accounts.login(fields.get("email"), fields.get("password"), source(request));
      loginSession(user, request, response);
      return redirect("/accounts/mfa/");
    } catch (DomainException e) {
      return invalid(
          model, e, fields, "Sign in", "Sign in", Map.of("email", "email", "password", "password"));
    }
  }

  @GetMapping("/accounts/verify/{token}/")
  String verify(@PathVariable String token, Model model) {
    model.addAttribute(
        "message", "Confirm email verification. This GET request does not consume the link.");
    return form(model, "Verify email", "Verify email", Map.of());
  }

  @PostMapping("/accounts/verify/{token}/")
  Object verify(
      @PathVariable String token,
      @RequestParam Map<String, String> fields,
      HttpServletRequest request,
      Model model) {
    AccountRules.fields(fields.keySet());
    accounts.verify(token);
    return complete(request, "Email verified", "Sign in and enroll an authenticator to continue.");
  }

  @GetMapping("/accounts/resend-verification/")
  String resend(Model model) {
    authz.identity();
    return form(model, "Resend verification", "Send verification", Map.of());
  }

  @PostMapping("/accounts/resend-verification/")
  Object resend(@RequestParam Map<String, String> fields, HttpServletRequest request, Model model) {
    AccountRules.fields(fields.keySet());
    accounts.resend(Db.uuid(authz.identity(), "id"));
    return complete(
        request,
        "Check captured email",
        "If this address is eligible, a replacement link has been sent to Mailpit.");
  }

  @GetMapping("/accounts/mfa/")
  Object mfa(Model model) {
    var user = authz.identity();
    if (!Boolean.TRUE.equals(user.get("verified")))
      return message(
          model,
          "Verify your email",
          "Open the verification email in Mailpit, or resend verification.");
    if (!Boolean.TRUE.equals(user.get("totp_confirmed"))) return redirect("/accounts/mfa/enroll/");
    return form(
        model, "Complete MFA", "Verify authenticator or recovery code", Map.of("code", "text"));
  }

  @PostMapping("/accounts/mfa/")
  Object mfa(
      @RequestParam Map<String, String> fields,
      HttpServletRequest request,
      HttpServletResponse response,
      Model model) {
    AccountRules.fields(fields.keySet(), "code");
    UUID user = Db.uuid(authz.identity(), "id");
    long epoch = accounts.completeMfa(user, fields.get("code"));
    request.changeSessionId();
    request.getSession().removeAttribute("step_up_at");
    request.getSession().setAttribute("epoch", epoch);
    request.getSession().setAttribute("mfa", true);
    return redirect("/");
  }

  @GetMapping("/accounts/mfa/enroll/")
  String enroll(Model model) {
    var user = authz.identity();
    String seed = accounts.enrollmentSeed(Db.uuid(user, "id"));
    model.addAttribute("seed", seed);
    model.addAttribute(
        "otp_uri",
        "otpauth://totp/MPRP:"
            + user.get("email")
            + "?secret="
            + seed
            + "&issuer=MPRP&algorithm=SHA1&digits=6&period=30");
    model.addAttribute(
        "message",
        "Add this secret to an authenticator, then enter a fresh six-digit code. Store it"
            + " securely.");
    return form(model, "Enroll authenticator", "Confirm enrollment", Map.of("code", "text"));
  }

  @PostMapping("/accounts/mfa/enroll/")
  Object enroll(@RequestParam Map<String, String> fields, HttpServletRequest request, Model model) {
    AccountRules.fields(fields.keySet(), "code");
    UUID user = Db.uuid(authz.identity(), "id");
    var codes = accounts.enroll(user, fields.get("code"));
    request.changeSessionId();
    request.getSession().setAttribute("mfa", true);
    retainCodes(request, codes);
    return complete(
        request,
        "Recovery codes",
        "Save these ten one-use codes now. Each recovery sign-in revokes earlier sessions.");
  }

  @GetMapping("/accounts/step-up/")
  String stepUp(Model model) {
    authz.current();
    return form(
        model,
        "Confirm a sensitive action",
        "Confirm for five minutes",
        Map.of("password", "password", "code", "text"));
  }

  @PostMapping("/accounts/step-up/")
  Object stepUp(@RequestParam Map<String, String> fields, HttpServletRequest request, Model model) {
    AccountRules.fields(fields.keySet(), "password", "code");
    accounts.sensitiveCheck(authz.current(), fields.get("password"), fields.get("code"));
    request.getSession().setAttribute("step_up_at", Instant.now());
    return complete(
        request,
        "Step-up complete",
        "Sensitive actions are available in this session for five minutes.");
  }

  @PostMapping("/accounts/logout/")
  Object logout(@RequestParam Map<String, String> fields, HttpServletRequest request) {
    AccountRules.fields(fields.keySet());
    if (request.getSession(false) != null) request.getSession(false).invalidate();
    SecurityContextHolder.clearContext();
    return redirect("/accounts/login/");
  }

  @PostMapping("/accounts/logout-all/")
  Object logoutAll(@RequestParam Map<String, String> fields, HttpServletRequest request) {
    AccountRules.fields(fields.keySet());
    accounts.logoutAll(authz.current());
    return logout(Map.of(), request);
  }

  @GetMapping("/accounts/reset/")
  String reset(Model model) {
    return form(model, "Reset sandbox password", "Send reset link", Map.of("email", "email"));
  }

  @PostMapping("/accounts/reset/")
  Object reset(@RequestParam Map<String, String> fields, HttpServletRequest request, Model model) {
    AccountRules.fields(fields.keySet(), "email");
    accounts.requestReset(fields.get("email"), source(request));
    return complete(
        request,
        "Check captured email",
        "If this address is eligible, a reset link has been sent to Mailpit. It expires after 30"
            + " minutes.");
  }

  @GetMapping("/accounts/reset/{token}/")
  String resetToken(@PathVariable String token, Model model) {
    return form(model, "Choose a new password", "Reset password", Map.of("password", "password"));
  }

  @PostMapping("/accounts/reset/{token}/")
  Object resetToken(
      @PathVariable String token,
      @RequestParam Map<String, String> fields,
      HttpServletRequest request,
      Model model) {
    AccountRules.fields(fields.keySet(), "password");
    accounts.resetPassword(token, fields.get("password"));
    if (request.getSession(false) != null) request.getSession(false).invalidate();
    SecurityContextHolder.clearContext();
    return complete(
        request,
        "Password changed",
        "Earlier sessions are revoked. Sign in again with your authenticator.");
  }

  @GetMapping("/accounts/profile/")
  String profile(Model model) {
    UUID user = authz.current();
    model.addAttribute(
        "user",
        db.one(
            "select id,email,verified,totp_confirmed,created_at,platform_role from app_user where"
                + " id=?",
            user));
    model.addAttribute(
        "memberships",
        db.query(
            "select m.id,m.name,m.status,x.role from merchant m join membership x on"
                + " x.merchant_id=m.id where x.user_id=? and x.active order by m.name",
            user));
    return "accounts/profile";
  }

  @GetMapping("/accounts/recovery-codes/")
  String recovery(Model model) {
    authz.current();
    return form(model, "Replace recovery codes", "Replace ten recovery codes", Map.of());
  }

  @PostMapping("/accounts/recovery-codes/")
  Object recovery(
      @RequestParam Map<String, String> fields, HttpServletRequest request, Model model) {
    AccountRules.fields(fields.keySet());
    UUID user = authz.current();
    authz.stepUp();
    retainCodes(request, accounts.replaceRecoveryCodes(user));
    return complete(
        request,
        "New recovery codes",
        "Save these codes now. Previously issued codes no longer work.");
  }

  @GetMapping("/accounts/deactivate/")
  String deactivate(Model model) {
    authz.current();
    model.addAttribute(
        "message",
        "This clears your credentials and revokes memberships and sessions. You must first transfer"
            + " ownership of every nonclosed merchant where you are the last active owner.");
    return form(model, "Deactivate account", "Deactivate account", Map.of());
  }

  @PostMapping("/accounts/deactivate/")
  Object deactivate(
      @RequestParam Map<String, String> fields, HttpServletRequest request, Model model) {
    AccountRules.fields(fields.keySet());
    UUID user = authz.current();
    authz.stepUp();
    accounts.deactivate(user);
    request.getSession().invalidate();
    SecurityContextHolder.clearContext();
    return complete(
        request,
        "Account deactivated",
        "Credentials have been cleared. Financial actor references remain retained.");
  }

  @GetMapping("/accounts/result/")
  String result(HttpServletRequest request, Model model) {
    HttpSession session = request.getSession(false);
    String title = "Account action", body = "Return to your account to continue.";
    if (session != null) {
      if (session.getAttribute("account_title") instanceof String t) title = t;
      if (session.getAttribute("account_message") instanceof String m) body = m;
      session.removeAttribute("account_title");
      session.removeAttribute("account_message");
      if (session.getAttribute("account_codes") instanceof String cipher
          && session.getAttribute("account_codes_context") instanceof String context) {
        model.addAttribute(
            "codes", java.util.List.of(secrets.decrypt(cipher, context).split("\n")));
        session.removeAttribute("account_codes");
        session.removeAttribute("account_codes_context");
      }
    }
    return message(model, title, body);
  }

  private void retainCodes(HttpServletRequest request, java.util.List<String> codes) {
    String context = "recovery-display:" + UUID.randomUUID();
    request.getSession().setAttribute("account_codes_context", context);
    request
        .getSession()
        .setAttribute("account_codes", secrets.encrypt(String.join("\n", codes), context));
  }

  private static RedirectView complete(HttpServletRequest request, String title, String body) {
    request.getSession().setAttribute("account_title", title);
    request.getSession().setAttribute("account_message", body);
    return redirect("/accounts/result/");
  }

  private void loginSession(
      Map<String, Object> user, HttpServletRequest request, HttpServletResponse response) {
    HttpSession old = request.getSession(false);
    if (old != null) old.invalidate();
    HttpSession session = request.getSession(true);
    request.changeSessionId();
    session.setMaxInactiveInterval(1800);
    session.setAttribute("epoch", Db.number(user, "auth_epoch"));
    session.setAttribute("login_at", Instant.now());
    session.setAttribute("mfa", false);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            user.get("id").toString(),
            null,
            java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    SecurityContextHolder.setContext(context);
    new HttpSessionSecurityContextRepository().saveContext(context, request, response);
  }

  public static String form(Model model, String title, String button, Map<String, String> inputs) {
    model.addAttribute("title", title);
    model.addAttribute("button", button);
    model.addAttribute("inputs", new java.util.TreeMap<>(inputs));
    return "accounts/form";
  }

  public static String message(Model model, String title, String message) {
    model.addAttribute("title", title);
    model.addAttribute("message", message);
    return "accounts/message";
  }

  private String invalid(
      Model model,
      DomainException e,
      Map<String, String> values,
      String title,
      String button,
      Map<String, String> inputs) {
    var attributes =
        (org.springframework.web.context.request.ServletRequestAttributes)
            org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
    if (attributes.getResponse() != null) attributes.getResponse().setStatus(e.status());
    model.addAttribute("correlation_id", UUID.randomUUID());
    model.addAttribute("error", e.getMessage());
    Map<String, String> safe = new LinkedHashMap<>(values);
    safe.remove("password");
    safe.remove("code");
    model.addAttribute("values", safe);
    return form(model, title, button, inputs);
  }

  public static RedirectView redirect(String target) {
    var view = new RedirectView(target);
    view.setStatusCode(HttpStatus.SEE_OTHER);
    return view;
  }

  private static String source(HttpServletRequest request) {
    return SecretCodec.digest(request.getRemoteAddr());
  }
}
