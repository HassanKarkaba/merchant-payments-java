package com.hassankarkaba.mprp.simulator;

import com.hassankarkaba.mprp.protocol.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProviderController {
  private final ProviderStore provider;
  private final ProviderStatements statements;

  public ProviderController(ProviderStore provider, ProviderStatements statements) {
    this.provider = provider;
    this.statements = statements;
  }

  private ResponseEntity<String> json(Object value) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(CanonicalJson.string(value));
  }

  private Map<String, Object> body(byte[] raw) {
    return CanonicalJson.parse(raw);
  }

  @GetMapping("/health/live/")
  ResponseEntity<String> live() {
    return json(Map.of("live", true, "sandbox", true));
  }

  @GetMapping("/v1/capabilities/")
  ResponseEntity<String> capabilities() {
    return json(provider.capabilities());
  }

  @PostMapping("/v1/control/handshake/")
  ResponseEntity<String> handshake(@RequestBody byte[] raw) {
    return json(provider.handshake(body(raw)));
  }

  @PostMapping("/v1/control/restore-release/")
  ResponseEntity<String> release(@RequestBody byte[] raw) {
    return json(provider.release(body(raw)));
  }

  @PostMapping({"/v1/{kind}/", "/v1/disputes/{action}/", "/v1/settlements/{action}/"})
  ResponseEntity<String> mutation(
      @PathVariable(required = false) String kind,
      @PathVariable(required = false) String action,
      @RequestBody byte[] raw,
      jakarta.servlet.http.HttpServletRequest request) {
    String k = kind;
    if (request.getRequestURI().startsWith("/v1/disputes/")) k = "disputes/" + action;
    if (request.getRequestURI().startsWith("/v1/settlements/")) k = "settlements/" + action;
    Map<String, Object> data = body(raw), result = provider.mutate(k, data);
    Map<String, Object> p = ProviderStore.payload(data);
    if ("tok_demo_timeout_after_commit".equals(p.getOrDefault("token", p.get("scenario")))
        && "SUCCEEDED".equals(result.get("state")))
      return ResponseEntity.status(503)
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              CanonicalJson.string(
                  Map.of(
                      "code",
                      "transport_uncertain",
                      "message",
                      "Independent provider commitment retained; resolve original key")));
    return json(result);
  }

  @PostMapping("/v1/resolve-key/")
  ResponseEntity<String> resolve(@RequestBody byte[] raw) {
    return json(provider.resolve(body(raw)));
  }

  @GetMapping("/v1/operations/{key}/")
  ResponseEntity<String> operation(
      @PathVariable String key, @RequestParam(required = false) UUID provider_account_id) {
    return json(provider.operation(key, provider_account_id));
  }

  @GetMapping("/v1/accounts/{id}/")
  ResponseEntity<String> account(@PathVariable UUID id) {
    return json(provider.accountSnapshot(id));
  }

  @PostMapping("/v1/accounts/{id}/retire/")
  ResponseEntity<String> retire(
      @PathVariable UUID id,
      @RequestBody byte[] raw,
      @RequestHeader(value = "X-MPRP-Purge-Secret", required = false) String purgeSecret) {
    provider.authorizePurgeSecret(purgeSecret);
    return json(provider.retire(id, body(raw)));
  }

  @GetMapping("/v1/accounts/{id}/retirement/")
  ResponseEntity<String> retirement(@PathVariable UUID id) {
    return json(provider.retirement(id));
  }

  @GetMapping({"/v1/payments/{id}/", "/v1/objects/{id}/", "/v1/disputes/{id}/"})
  ResponseEntity<String> object(@PathVariable UUID id) {
    return json(provider.snapshot(id));
  }

  @GetMapping("/v1/history/{id}/")
  ResponseEntity<String> history(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(required = false) Long snapshot) {
    if (after < 0) throw ProviderException.invalid("Invalid history cursor");
    return json(provider.history(id, after, snapshot));
  }

  @GetMapping("/v1/events/")
  ResponseEntity<String> events(@RequestParam(defaultValue = "0") long after) {
    if (after < 0) throw ProviderException.invalid("Invalid event cursor");
    return json(provider.events(after));
  }

  @PostMapping("/v1/events/ack/")
  ResponseEntity<String> acknowledge(@RequestBody byte[] raw) {
    return json(provider.acknowledge(body(raw)));
  }

  @GetMapping("/v1/inventory/")
  ResponseEntity<String> inventory(
      @RequestParam(defaultValue = "0") long after, @RequestParam(required = false) Long snapshot) {
    if (after < 0 || after > 10000000) throw ProviderException.invalid("Invalid inventory cursor");
    return json(provider.inventory(false, after, snapshot));
  }

  @GetMapping("/v1/inventory/summary/")
  ResponseEntity<String> summary(@RequestParam(required = false) UUID provider_account_id) {
    return json(provider.inventorySummary(provider_account_id));
  }

  @GetMapping("/v1/inventory/sources/")
  ResponseEntity<String> sources(
      @RequestParam(defaultValue = "0") long after, @RequestParam(required = false) Long snapshot) {
    if (after < 0) throw ProviderException.invalid("Invalid inventory cursor");
    return json(provider.inventory(true, after, snapshot));
  }

  @GetMapping("/v1/statements/")
  ResponseEntity<String> statement(
      @RequestParam UUID provider_account_id,
      @RequestParam String kind,
      @RequestParam String period_start,
      @RequestParam String period_end) {
    return json(
        statements.statement(
            provider_account_id, kind, Instant.parse(period_start), Instant.parse(period_end)));
  }

  @PostMapping("/v1/statements/")
  ResponseEntity<String> statementPost(@RequestBody byte[] raw) {
    Map<String, Object> d = body(raw);
    return json(
        statements.statement(
            ProviderStore.uuid(d.getOrDefault("provider_account_id", d.get("merchant_id"))),
            ProviderStore.text(d, "kind"),
            Instant.parse(
                ProviderStore.text(d, d.containsKey("period_start") ? "period_start" : "start")),
            Instant.parse(
                ProviderStore.text(d, d.containsKey("period_end") ? "period_end" : "end"))));
  }

  @GetMapping("/v1/statements/{id}/")
  ResponseEntity<String> manifest(@PathVariable UUID id) {
    return json(statements.manifest(id));
  }

  @GetMapping("/v1/statements/{id}/csv/")
  ResponseEntity<byte[]> csv(@PathVariable UUID id) {
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header("Content-Disposition", "attachment; filename=\"sim_" + id + ".csv\"")
        .body(statements.csv(id));
  }

  @ExceptionHandler(ProviderException.class)
  ResponseEntity<String> error(ProviderException e) {
    return ResponseEntity.status(e.status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(CanonicalJson.string(Map.of("code", e.code, "message", e.getMessage())));
  }

  @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
  ResponseEntity<String> invalid(RuntimeException e) {
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            CanonicalJson.string(
                Map.of(
                    "code",
                    "invalid_input",
                    "message",
                    "Invalid bounded synthetic service input")));
  }
}
