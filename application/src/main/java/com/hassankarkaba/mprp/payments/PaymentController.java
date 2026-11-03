package com.hassankarkaba.mprp.payments;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.*;
import com.hassankarkaba.mprp.web.Web;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class PaymentController {
  private final PaymentService service;
  private final Db db;
  private final Authz auth;
  private final PaymentRepository repository;
  private final com.hassankarkaba.mprp.accounts.SecretCodec secrets;

  public PaymentController(
      PaymentService service,
      Db db,
      Authz auth,
      PaymentRepository repository,
      com.hassankarkaba.mprp.accounts.SecretCodec secrets) {
    this.service = service;
    this.db = db;
    this.auth = auth;
    this.repository = repository;
    this.secrets = secrets;
  }

  @GetMapping("/m/{merchant}/payments/")
  String list(@PathVariable UUID merchant, @RequestParam(defaultValue = "0") int page, Model m) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    if (page < 0 || page > 100000) throw new DomainException(400, "Invalid page");
    repository.findByMerchantIdOrderByIdDesc(
        merchant, org.springframework.data.domain.PageRequest.of(page, 50));
    m.addAttribute("merchant", merchant);
    m.addAttribute(
        "payments",
        db.query(
            "select id,status,captured_minor,refunded_minor,created_at from payment where"
                + " merchant_id=? order by created_at desc,id limit 50 offset ?",
            merchant,
            page * 50));
    m.addAttribute(
        "links",
        db.query(
            "select id,amount_minor,capture_mode,expires_at,consumed_at,revoked_at from"
                + " payment_link where merchant_id=? order by created_at desc limit 50",
            merchant));
    return "payments";
  }

  @GetMapping("/m/{merchant}/links/new/")
  String linkForm(@PathVariable UUID merchant, Model m) {
    auth.require(merchant, "OWNER", "FINANCE");
    return Web.form(
        m,
        "Create payment link",
        "/m/" + merchant + "/links/new/",
        new LinkedHashMap<>(
            Map.of("amount", "100.00", "capture_mode", "AUTOMATIC", "expiry_minutes", "1440")));
  }

  @PostMapping("/m/{merchant}/links/new/")
  ResponseEntity<Void> newLink(
      @PathVariable UUID merchant, @RequestParam Map<String, String> f, HttpSession s) {
    Web.fields(f, "amount", "capture_mode", "expiry_minutes");
    String token =
        service.link(
            merchant,
            f.get("amount"),
            f.get("capture_mode"),
            Integer.parseInt(f.get("expiry_minutes")));
    s.setAttribute("issued_link", secrets.encrypt(token, "issued-link:" + merchant));
    return see("/m/" + merchant + "/links/issued/");
  }

  @GetMapping("/m/{merchant}/links/issued/")
  String issued(@PathVariable UUID merchant, HttpSession s, Model m) {
    auth.require(merchant, "OWNER", "FINANCE");
    String cipher = (String) s.getAttribute("issued_link");
    String token = cipher == null ? null : secrets.decrypt(cipher, "issued-link:" + merchant);
    s.removeAttribute("issued_link");
    m.addAttribute("token", token);
    return "link-issued";
  }

  @PostMapping("/m/{merchant}/links/{link}/revoke/")
  ResponseEntity<Void> revoke(@PathVariable UUID merchant, @PathVariable UUID link) {
    service.revoke(merchant, link);
    return see("/m/" + merchant + "/payments/");
  }

  @GetMapping("/m/{merchant}/payments/{payment}/")
  String detail(@PathVariable UUID merchant, @PathVariable UUID payment, Model m) {
    auth.require(merchant, "OWNER", "FINANCE", "ANALYST");
    m.addAttribute(
        "payment", db.one("select * from payment where id=? and merchant_id=?", payment, merchant));
    m.addAttribute(
        "operations",
        db.query(
            "select id,kind,status,reason,created_at from operation where resource_id=? and"
                + " merchant_id=? order by created_at",
            payment,
            merchant));
    m.addAttribute("action_key", PaymentService.secret());
    m.addAttribute("merchant", merchant);
    return "payment";
  }

  @GetMapping({
    "/m/{merchant}/payments/{payment}/capture/",
    "/m/{merchant}/payments/{payment}/void/",
    "/m/{merchant}/payments/{payment}/refunds/new/"
  })
  String actionForm(
      @PathVariable UUID merchant,
      @PathVariable UUID payment,
      jakarta.servlet.http.HttpServletRequest r,
      Model m) {
    auth.require(merchant, "OWNER", "FINANCE");
    db.one("select id from payment where id=? and merchant_id=?", payment, merchant);
    String kind =
        r.getRequestURI().contains("refunds")
            ? "refund"
            : r.getRequestURI().contains("capture") ? "capture" : "void";
    var fields = new LinkedHashMap<String, String>();
    fields.put("action_key", PaymentService.secret());
    if (!kind.equals("void")) fields.put("amount", "");
    if (kind.equals("refund")) fields.put("reason", "");
    return Web.form(m, kind + " payment", r.getRequestURI(), fields);
  }

  @PostMapping({
    "/m/{merchant}/payments/{payment}/capture/",
    "/m/{merchant}/payments/{payment}/void/",
    "/m/{merchant}/payments/{payment}/refunds/new/"
  })
  ResponseEntity<Void> action(
      @PathVariable UUID merchant,
      @PathVariable UUID payment,
      @RequestParam Map<String, String> f,
      jakarta.servlet.http.HttpServletRequest r) {
    Web.fields(f, "action_key", "amount", "reason");
    String kind =
        r.getRequestURI().contains("refunds")
            ? "refund"
            : r.getRequestURI().contains("capture") ? "capture" : "void";
    UUID op =
        service.action(
            merchant, payment, kind, f.get("amount"), f.get("reason"), f.get("action_key"));
    return see("/m/" + merchant + "/operations/" + op + "/");
  }

  @GetMapping("/checkout/{token}/")
  String checkout(@PathVariable String token, HttpSession s, Model m) {
    s.setAttribute(
        "guest_secret",
        s.getAttribute("guest_secret") == null
            ? PaymentService.secret()
            : s.getAttribute("guest_secret"));
    String attemptKey = "attempt_" + PaymentService.digest(token);
    if (s.getAttribute(attemptKey) == null) s.setAttribute(attemptKey, PaymentService.secret());
    m.addAttribute("link", service.linkInfo(token));
    m.addAttribute("token", token);
    m.addAttribute("attempt", s.getAttribute(attemptKey));
    return "checkout";
  }

  @PostMapping("/checkout/{token}/confirm/")
  ResponseEntity<Void> confirm(
      @PathVariable String token, @RequestParam Map<String, String> f, HttpSession s) {
    Web.fields(f, "attempt_key", "customer_name", "payment_token");
    String secret = (String) s.getAttribute("guest_secret");
    if (secret == null)
      throw new DomainException(403, "Open checkout in this browser before confirming");
    UUID receipt =
        service.checkout(
            token, secret, f.get("attempt_key"), f.get("customer_name"), f.get("payment_token"));
    return see("/checkout/result/" + receipt + "/");
  }

  @GetMapping("/checkout/result/{receipt}/")
  String receipt(@PathVariable UUID receipt, HttpSession s, Model m) {
    String secret = (String) s.getAttribute("guest_secret");
    if (secret == null) throw new DomainException(404, "Receipt not found");
    m.addAttribute("payment", service.receipt(receipt, secret));
    return "receipt";
  }

  private ResponseEntity<Void> see(String path) {
    return ResponseEntity.status(303).location(java.net.URI.create(path)).build();
  }
}
