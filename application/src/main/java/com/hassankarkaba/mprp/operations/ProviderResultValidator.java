package com.hassankarkaba.mprp.operations;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.protocol.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ProviderResultValidator {
  private final Db db;

  public ProviderResultValidator(Db db) {
    this.db = db;
  }

  @SuppressWarnings("unchecked")
  public void validate(UUID merchant, Map<String, Object> operation, Map<String, Object> result) {
    var c = db.one("select * from system_control where id=true");
    require(
        result.get("schema_version") instanceof Number
            && ((Number) result.get("schema_version")).intValue() == 1
            && "mprp_v1".equals(result.get("policy_version"))
            && c.get("environment_id").toString().equals(result.get("environment_id")),
        409,
        "Provider schema/policy/environment mismatch");
    require(
        "USD".equals(result.get("currency"))
            && merchant.toString().equals(result.get("provider_account_id")),
        409,
        "Provider account/currency mismatch");
    require(
        c.get("provider_instance_id") != null
            && c.get("provider_instance_id").toString().equals(result.get("simulator_instance_id")),
        409,
        "Provider instance mismatch");
    require(
        Set.of("SUCCEEDED", "FAILED", "NO_EFFECT").contains(result.get("state")),
        409,
        "Provider result not definitive");
    if (operation != null) {
      require(
          operation.get("provider_key").equals(result.get("operation_key"))
              && operation.get("fingerprint").equals(result.get("payload_hash"))
              && operation.get("resource_id").toString().equals(result.get("resource_id")),
          409,
          "Provider request/result identity mismatch");
    }
    require(
        result.get("sources") instanceof List<?>
            && ((List<?>) result.get("sources")).size() <= 10000,
        409,
        "Invalid source history");
    var sources = (List<Map<String, Object>>) result.get("sources");
    Set<String> seen = new HashSet<>();
    Map<UUID, Set<Long>> captureAdmissions = captureAdmissions(merchant, sources);
    Map<UUID, Long> capture = new HashMap<>(), fees = new HashMap<>(), refunds = new HashMap<>();
    long sequence = -1;
    for (var s : sources) {
      String type = Objects.toString(s.get("event_type"), "");
      require(
          Set.of(
                  "CAPTURE",
                  "FEE",
                  "REFUND",
                  "DISPUTE_OPEN",
                  "DISPUTE_WON",
                  "CORRECTION_CREDIT",
                  "CORRECTION_DEBIT",
                  "PAYOUT",
                  "SETTLEMENT",
                  "BATCH_ZERO")
              .contains(type),
          409,
          "Unsupported source event");
      String source = Objects.toString(s.get("source_id"), "");
      require(
          source.matches("[A-Za-z0-9_.-]{1,128}") && seen.add(source + ":" + type),
          409,
          "Duplicate/invalid source identity");
      require(
          merchant.toString().equals(s.get("provider_account_id"))
              && "USD".equals(s.get("currency")),
          409,
          "Source scope mismatch");
      long amount = number(s.get("amount_minor"));
      require(
          Math.abs(amount) <= 100000000000000L && amount != Long.MIN_VALUE,
          409,
          "Source amount out of bounds");
      require(
          switch (type) {
            case "CAPTURE", "DISPUTE_WON", "CORRECTION_CREDIT" -> amount > 0;
            case "FEE", "REFUND", "DISPUTE_OPEN", "CORRECTION_DEBIT", "PAYOUT" -> amount < 0;
            case "BATCH_ZERO" -> amount == 0;
            default -> amount != 0;
          },
          409,
          "Source sign mismatch");
      UUID resource = UUID.fromString(s.get("resource_id").toString());
      Instant at = Instant.parse(s.get("occurred_at").toString());
      require(!at.isAfter(Instant.now().plusSeconds(300)), 409, "Future source time");
      long seq = number(s.get("sequence"));
      require(seq > sequence, 409, "Source history is not ordered");
      sequence = seq;
      if (type.equals("CAPTURE")) {
        require(!capture.containsKey(resource), 409, "More than one final capture");
        capture.put(resource, amount);
        require(
            captureAdmissions.getOrDefault(resource, Set.of()).contains(amount),
            409,
            "Capture does not match a frozen local final capture admission");
      }
      if (type.equals("FEE")) {
        require(!fees.containsKey(resource), 409, "Duplicate capture fee");
        fees.put(resource, Math.negateExact(amount));
      }
      if (type.equals("REFUND")) refunds.merge(resource, Math.negateExact(amount), Math::addExact);
      if (!type.equals("PAYOUT"))
        require(s.get("batch_id") instanceof String, 409, "Source batch identity missing");
    }
    for (var cap : capture.entrySet()) {
      require(
          fees.getOrDefault(cap.getKey(), 0L) == Money.fee(cap.getValue()),
          409,
          "Capture fee policy mismatch");
      require(
          refunds.getOrDefault(cap.getKey(), 0L) <= cap.getValue(),
          409,
          "Refund history exceeds capture");
    }
    for (var fee : fees.entrySet())
      require(capture.containsKey(fee.getKey()), 409, "Fee without complete capture history");
    if (operation != null && result.get("state").equals("SUCCEEDED")) {
      String kind = operation.get("kind").toString();
      UUID resource = Db.uuid(operation, "resource_id");
      var payload = CanonicalJson.parse(operation.get("payload").toString());
      if (Set.of("authorize", "capture", "refund", "void").contains(kind)) {
        long captured = number(result.getOrDefault("captured_minor", 0)),
            refunded = number(result.getOrDefault("refunded_minor", 0));
        require(
            captured == capture.getOrDefault(resource, 0L)
                && refunded == refunds.getOrDefault(resource, 0L),
            409,
            "Payment totals disagree with complete sources");
      }
      if (kind.equals("capture"))
        require(
            number(result.get("captured_minor")) == number(payload.get("amount_minor")),
            409,
            "Capture result differs from admitted amount");
      if (kind.equals("authorize")
          && Set.of("AUTHORIZED", "CAPTURED").contains(result.get("business_state")))
        require(
            number(result.get("authorized_minor")) == number(payload.get("amount_minor")),
            409,
            "Authorization result differs from admitted amount");
      if (kind.equals("refund")) {
        var principal =
            sources.stream()
                .filter(
                    x ->
                        x.get("event_type").equals("REFUND")
                            && x.get("resource_id").equals(resource.toString()))
                .toList();
        require(
            !principal.isEmpty()
                && Math.negateExact(number(principal.getLast().get("amount_minor")))
                    == number(payload.get("amount_minor")),
            409,
            "Refund result differs from admitted principal");
      }
      if (kind.equals("payout")) {
        var list = sources.stream().filter(s -> s.get("event_type").equals("PAYOUT")).toList();
        require(
            list.size() == 1
                && list.getFirst().get("resource_id").equals(resource.toString())
                && Math.negateExact(number(list.getFirst().get("amount_minor")))
                    == number(payload.get("amount_minor")),
            409,
            "Payout amount/identity mismatch");
        Object beneficiary = payload.getOrDefault("beneficiary_token", payload.get("beneficiary"));
        require(
            beneficiary != null
                && beneficiary.equals(
                    result.getOrDefault("beneficiary_token", result.get("beneficiary"))),
            409,
            "Payout destination mismatch");
      }
    }
  }

  private long number(Object n) {
    require(
        n instanceof Byte
            || n instanceof Short
            || n instanceof Integer
            || n instanceof Long
            || n instanceof java.math.BigInteger,
        409,
        "Provider amount must be an exact integer");
    return n instanceof java.math.BigInteger b ? b.longValueExact() : ((Number) n).longValue();
  }

  private Map<UUID, Set<Long>> captureAdmissions(UUID merchant, List<Map<String, Object>> sources) {
    List<UUID> resources =
        sources.stream()
            .filter(x -> "CAPTURE".equals(x.get("event_type")))
            .map(x -> UUID.fromString(x.get("resource_id").toString()))
            .distinct()
            .toList();
    Map<UUID, Set<Long>> allowed = new HashMap<>();
    for (int start = 0; start < resources.size(); start += 250) {
      var chunk = resources.subList(start, Math.min(start + 250, resources.size()));
      String marks = String.join(",", Collections.nCopies(chunk.size(), "?"));
      List<Object> args = new ArrayList<>();
      args.add(merchant);
      args.addAll(chunk);
      for (var op :
          db.query(
              "select resource_id,kind,payload from operation where merchant_id=? and resource_id"
                  + " in("
                  + marks
                  + ") and kind in('authorize','capture')",
              args.toArray())) {
        var payload = CanonicalJson.parse(op.get("payload").toString());
        if (op.get("kind").equals("capture") || "AUTOMATIC".equals(payload.get("capture_mode")))
          allowed
              .computeIfAbsent(Db.uuid(op, "resource_id"), k -> new HashSet<>())
              .add(number(payload.get("amount_minor")));
      }
    }
    return allowed;
  }
}
