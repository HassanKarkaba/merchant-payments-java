package com.hassankarkaba.mprp.simulator;

import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderStatements {
  private final ProviderStore store;

  public ProviderStatements(ProviderStore store) {
    this.store = store;
  }

  static final List<String> PROCESSOR =
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
  static final List<String> BANK =
      List.of(
          "schema_version",
          "line_id",
          "bank_account_id",
          "booked_at",
          "currency",
          "amount_minor",
          "transfer_reference",
          "description");

  @Transactional
  public Map<String, Object> statement(UUID account, String kind, Instant start, Instant end) {
    if (!Set.of("PROCESSOR_ECONOMIC", "PROCESSOR_TRANSFER", "BANK").contains(kind)
        || !start.isBefore(end)
        || Duration.between(start, end).compareTo(Duration.ofDays(31)) > 0)
      throw ProviderException.invalid("Invalid statement kind or half-open period");
    store.one("SELECT id FROM sim_accounts WHERE id=? AND retired=false FOR UPDATE", account);
    long high = store.count("SELECT source_sequence FROM sim_control WHERE id=1");
    boolean bank = "BANK".equals(kind);
    String predicate =
        "PROCESSOR_ECONOMIC".equals(kind)
            ? "clearing_line=true"
            : bank ? "clearing_line=false AND event_type<>'BATCH_ZERO'" : "clearing_line=false";
    List<Map<String, Object>> sources =
        store.rows(
            "SELECT * FROM sim_sources WHERE account_id=? AND "
                + predicate
                + " AND occurred_at>=? AND occurred_at<? AND sequence<=? ORDER BY sequence LIMIT"
                + " 10001",
            account,
            Timestamp.from(start),
            Timestamp.from(end),
            high);
    long rowCount =
        store.count(
            "SELECT count(*) FROM sim_sources WHERE account_id=? AND "
                + predicate
                + " AND occurred_at>=? AND occurred_at<? AND sequence<=?",
            account,
            Timestamp.from(start),
            Timestamp.from(end),
            high);
    long opening =
        decimal(
            store
                .one(
                    "SELECT COALESCE(sum(amount_minor),0) AS n FROM sim_sources WHERE account_id=?"
                        + " AND "
                        + predicate
                        + " AND occurred_at<? AND sequence<=?",
                    account,
                    Timestamp.from(start),
                    high)
                .get("n"));
    long net =
        decimal(
            store
                .one(
                    "SELECT COALESCE(sum(amount_minor),0) AS n FROM sim_sources WHERE account_id=?"
                        + " AND "
                        + predicate
                        + " AND occurred_at>=? AND occurred_at<? AND sequence<=?",
                    account,
                    Timestamp.from(start),
                    Timestamp.from(end),
                    high)
                .get("n"));
    String declaredAccount = account.toString();
    List<Map<String, Object>> rows = new ArrayList<>();
    if (rowCount <= 10000) {
      for (Map<String, Object> s : sources) {
        String id = s.get("source_id").toString(),
            at = CanonicalJson.instant(ProviderStore.instant(s.get("occurred_at")));
        Map<String, Object> row;
        if (bank) {
          row =
              ProviderStore.map(
                  "schema_version",
                  1,
                  "line_id",
                  id,
                  "bank_account_id",
                  declaredAccount,
                  "booked_at",
                  at,
                  "currency",
                  "USD",
                  "amount_minor",
                  ((Number) s.get("amount_minor")).longValue(),
                  "transfer_reference",
                  s.get("reference").toString(),
                  "description",
                  s.get("event_type").toString() + " synthetic transfer");
        } else {
          row =
              ProviderStore.map(
                  "schema_version",
                  1,
                  "line_id",
                  id,
                  "provider_account_id",
                  declaredAccount,
                  "event_type",
                  s.get("event_type").toString(),
                  "source_id",
                  id,
                  "batch_id",
                  Objects.toString(s.get("batch_id"), ""),
                  "occurred_at",
                  at,
                  "currency",
                  "USD",
                  "amount_minor",
                  ((Number) s.get("amount_minor")).longValue(),
                  "reference",
                  s.get("reference").toString());
        }
        rows.add(row);
      }
    }
    if (rowCount > 10000) rows.clear();
    UUID id = UUID.randomUUID();
    List<String> header = bank ? BANK : PROCESSOR;
    StringBuilder csv = new StringBuilder(String.join(",", header)).append('\n');
    for (Map<String, Object> row : rows) {
      for (int i = 0; i < header.size(); i++) {
        if (i > 0) csv.append(',');
        String value = Objects.toString(row.get(header.get(i)), "");
        if (value.contains(",")
            || value.contains("\"")
            || value.contains("\n")
            || value.contains("\r"))
          csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        else csv.append(value);
      }
      csv.append('\n');
    }
    byte[] raw = csv.toString().getBytes(StandardCharsets.UTF_8);
    String content =
        CanonicalJson.sha256(ProviderStore.map("rows", rows, "source_high_water", high));
    List<Map<String, Object>> old =
        store.rows(
            "SELECT manifest FROM sim_manifests WHERE account_id=? AND kind=? AND period_start=?"
                + " AND period_end=? AND content_hash=?",
            account,
            kind,
            Timestamp.from(start),
            Timestamp.from(end),
            content);
    if (!old.isEmpty()) return CanonicalJson.parse(old.getFirst().get("manifest").toString());
    Map<String, Object> manifest =
        ProviderStore.map(
            "schema_version",
            1,
            "policy_version",
            "mprp_v1",
            "manifest_id",
            id,
            "statement_id",
            "sim_" + id.toString().replace("-", ""),
            "provider_account_id",
            account,
            "account_id",
            declaredAccount,
            "kind",
            kind,
            "period_start",
            CanonicalJson.instant(start),
            "period_end",
            CanonicalJson.instant(end),
            "opening_balance_minor",
            opening,
            "closing_balance_minor",
            Math.addExact(opening, net),
            "row_count",
            rowCount,
            "net_minor",
            net,
            "source_high_water",
            high,
            "high_water",
            high,
            "revision",
            1,
            "content_hash",
            content,
            "file_digest",
            CanonicalJson.digest(raw),
            "csv_sha256",
            CanonicalJson.digest(raw),
            "row_hashes",
            rows.stream().map(CanonicalJson::sha256).toList(),
            "rows",
            rows,
            "requires_split",
            rowCount > 10000);
    if (rowCount <= 10000)
      store.exec(
          "INSERT INTO"
              + " sim_manifests(id,account_id,statement_id,kind,period_start,period_end,content_hash,manifest,csv_content)"
              + " VALUES(?,?,?,?,?,?,?,?::jsonb,?)",
          id,
          account,
          manifest.get("statement_id"),
          kind,
          Timestamp.from(start),
          Timestamp.from(end),
          content,
          CanonicalJson.string(manifest),
          raw);
    return manifest;
  }

  private long decimal(Object n) {
    return new BigDecimal(n.toString()).longValueExact();
  }

  public Map<String, Object> manifest(UUID id) {
    return CanonicalJson.parse(
        store.one("SELECT manifest FROM sim_manifests WHERE id=?", id).get("manifest").toString());
  }

  public byte[] csv(UUID id) {
    return (byte[])
        store.one("SELECT csv_content FROM sim_manifests WHERE id=?", id).get("csv_content");
  }
}
