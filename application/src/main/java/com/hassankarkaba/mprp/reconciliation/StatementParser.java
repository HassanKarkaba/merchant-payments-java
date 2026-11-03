package com.hassankarkaba.mprp.reconciliation;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.operations.DomainException;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.io.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import org.apache.commons.csv.*;

/** Parses the whole bounded file. No persistence or economic mutation occurs here. */
public final class StatementParser {
  public static final List<String> PROCESSOR =
      List.of(
          "schema_version",
          "line_id",
          "provider_account_id",
          "event_type",
          "source_id",
          "batch_id",
          "occurred_at",
          "currency",
          "amount_minor",
          "reference");
  public static final List<String> BANK =
      List.of(
          "schema_version",
          "line_id",
          "bank_account_id",
          "booked_at",
          "currency",
          "amount_minor",
          "transfer_reference",
          "description");

  public record Parsed(List<Map<String, Object>> rows, List<String> errors) {
    public boolean valid() {
      return errors.isEmpty();
    }
  }

  private static final Set<String> POSITIVE = Set.of("CAPTURE", "CORRECTION_CREDIT", "DISPUTE_WON");
  private static final Set<String> NEGATIVE =
      Set.of("FEE", "REFUND", "DISPUTE_OPEN", "CORRECTION_DEBIT", "PAYOUT");

  private StatementParser() {}

  public static Parsed parse(
      byte[] bytes,
      String kind,
      String account,
      Instant start,
      Instant end,
      int count,
      long net,
      long opening,
      long closing) {
    List<Map<String, Object>> rows = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    require(bytes.length <= 5 * 1024 * 1024, 413, "CSV exceeds 5 MiB");
    require(
        Set.of("PROCESSOR_ECONOMIC", "PROCESSOR_TRANSFER", "BANK").contains(kind),
        400,
        "Unsupported statement kind");
    require(
        end.isAfter(start) && Duration.between(start, end).compareTo(Duration.ofDays(31)) <= 0,
        400,
        "Statement interval must be positive and at most 31 days");
    require(count >= 0 && count <= 10000, 400, "Declared rows out of range");
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      return new Parsed(List.of(), List.of("File must be strict UTF-8"));
    }
    if (text.indexOf('\0') >= 0
        || text.startsWith("PK")
        || text.startsWith("%PDF")
        || (bytes.length > 1 && (bytes[0] & 255) == 208 && (bytes[1] & 255) == 207))
      return new Parsed(List.of(), List.of("Only plain UTF-8 CSV is accepted"));
    Set<String> ids = new HashSet<>();
    BigInteger total = BigInteger.ZERO;
    List<String> header = kind.equals("BANK") ? BANK : PROCESSOR;
    try (CSVParser parser =
        CSVFormat.RFC4180
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(false)
            .setAllowMissingColumnNames(false)
            .get()
            .parse(new StringReader(text))) {
      if (!parser.getHeaderNames().equals(header))
        return new Parsed(List.of(), List.of("Incorrect header names or order"));
      for (CSVRecord r : parser) {
        int row = (int) r.getRecordNumber();
        if (row > 10000) {
          errors.add("At most 10000 data rows are accepted");
          break;
        }
        try {
          require(r.size() == header.size(), 400, "Incorrect column count");
          Map<String, Object> data = new LinkedHashMap<>();
          for (String h : header) {
            String value = r.get(h);
            require(
                value.codePointCount(0, value.length()) <= 4096,
                400,
                "Field exceeds 4096 characters");
            data.put(h, value);
          }
          require(r.get("schema_version").equals("1"), 400, "Unsupported schema version");
          String line = r.get("line_id");
          require(identifier(line), 400, "Invalid line identity");
          require(ids.add(line), 400, "Duplicate line identity");
          require(r.get("currency").equals("USD"), 400, "Only simulated USD is accepted");
          String amount = r.get("amount_minor");
          require(
              amount.matches("-?[0-9]+") && !amount.startsWith("-0"),
              400,
              "Invalid integer amount");
          BigInteger a = new BigInteger(amount);
          require(
              a.abs().compareTo(BigInteger.valueOf(100000000000000L)) <= 0,
              400,
              "Amount exceeds statement bound");
          long minor = a.longValueExact();
          String timestamp = kind.equals("BANK") ? r.get("booked_at") : r.get("occurred_at");
          Instant occurred = OffsetDateTime.parse(timestamp).toInstant();
          require(
              !occurred.isBefore(start) && occurred.isBefore(end),
              400,
              "Timestamp outside declared interval");
          if (kind.equals("BANK")) {
            require(r.get("bank_account_id").equals(account), 400, "Foreign bank account");
            require(minor != 0, 400, "Bank amounts must be nonzero");
            require(identifier(r.get("transfer_reference")), 400, "Invalid transfer reference");
            require(
                r.get("description").codePointCount(0, r.get("description").length()) <= 2000,
                400,
                "Description exceeds 2000 characters");
          } else {
            require(r.get("provider_account_id").equals(account), 400, "Foreign provider account");
            String event = r.get("event_type");
            require(
                identifier(r.get("source_id")) && identifier(r.get("reference")),
                400,
                "Invalid source identity");
            require(
                event.equals("PAYOUT") || identifier(r.get("batch_id")),
                400,
                "Missing immutable batch identity");
            if (kind.equals("PROCESSOR_TRANSFER"))
              require(
                  Set.of("SETTLEMENT", "PAYOUT", "BATCH_ZERO").contains(event),
                  400,
                  "Wrong transfer event");
            else
              require(
                  POSITIVE.contains(event) || NEGATIVE.contains(event),
                  400,
                  "Wrong economic event");
            if (POSITIVE.contains(event))
              require(minor > 0, 400, "Positive event has invalid sign");
            if (NEGATIVE.contains(event))
              require(minor < 0, 400, "Negative event has invalid sign");
            if (event.equals("BATCH_ZERO")) require(minor == 0, 400, "BATCH_ZERO must be zero");
            if (event.equals("SETTLEMENT")) require(minor != 0, 400, "Settlement must be nonzero");
          }
          data.put(
              kind.equals("BANK") ? "booked_at" : "occurred_at", CanonicalJson.instant(occurred));
          data.put("amount_minor", minor);
          data.put("schema_version", 1);
          rows.add(data);
          total = total.add(a);
        } catch (RuntimeException e) {
          if (errors.size() < 2000)
            errors.add(
                "Row "
                    + row
                    + ": "
                    + (e instanceof DomainException
                        ? e.getMessage()
                        : "Invalid field representation"));
        }
      }
    } catch (Exception e) {
      if (errors.size() < 2000) errors.add("Malformed CSV quoting or header");
    }
    if (rows.size() != count && errors.size() < 2000)
      errors.add("Declared count does not equal valid rows");
    if (!total.equals(BigInteger.valueOf(net)) && errors.size() < 2000)
      errors.add("Declared net does not equal row total");
    if (!BigInteger.valueOf(closing)
            .subtract(BigInteger.valueOf(opening))
            .equals(BigInteger.valueOf(net))
        && errors.size() < 2000) errors.add("Opening and closing balances do not agree with net");
    return new Parsed(List.copyOf(rows), List.copyOf(errors));
  }

  public static boolean identifier(String s) {
    return s != null && s.matches("[A-Za-z0-9_.-]{1,64}");
  }
}
