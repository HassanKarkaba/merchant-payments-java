package com.hassankarkaba.mprp.operations;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.accounting.LedgerService;
import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One authenticated, bounded history page per leased claim. Staging never posts money. */
@Service
public class ProviderHistoryService {
  public static final int MAX_SOURCES = 10000;
  private final Db db;
  private final ProviderClient provider;
  private final TransactionTemplate tx;
  private final Authz auth;
  private final AuditService audit;
  private final ProviderResultValidator validator;
  private static final Set<String> TRANSFERS = Set.of("SETTLEMENT", "PAYOUT", "BATCH_ZERO");

  private record Prepared(
      UUID task,
      UUID merchant,
      UUID resource,
      String digest,
      long highWater,
      long expected,
      long cursor,
      boolean complete) {}

  private record PageData(List<Map<String, Object>> sources, long next, boolean complete) {}

  public ProviderHistoryService(
      Db db,
      ProviderClient provider,
      PlatformTransactionManager manager,
      Authz auth,
      AuditService audit,
      ProviderResultValidator validator) {
    this.db = db;
    this.provider = provider;
    this.tx = new TransactionTemplate(manager);
    this.auth = auth;
    this.audit = audit;
    this.validator = validator;
  }

  /**
   * Empty retains responsibility: the caller must reschedule the same operation/key, or show
   * MANUAL.
   */
  public Optional<Map<String, Object>> expand(
      Map<String, Object> claim, Map<String, Object> summary) {
    if (!Boolean.TRUE.equals(summary.get("history_required"))) return Optional.of(summary);
    require(
        !TransactionSynchronizationManager.isActualTransactionActive(),
        409,
        "Provider history HTTP must run outside financial transactions");
    Prepared prepared = tx.execute(s -> prepare(claim, summary));
    if (prepared == null) return Optional.empty();
    if (prepared.complete) return load(prepared.task, summary);
    Map<String, Object> response =
        provider.get(
            "/v1/history/"
                + prepared.resource
                + "/?after="
                + prepared.cursor
                + "&snapshot="
                + prepared.highWater);
    PageData page;
    try {
      page = validatePage(prepared, response);
    } catch (RuntimeException malformed) {
      tx.executeWithoutResult(
          s -> {
            if (fence(claim))
              manualTask(
                  prepared.task,
                  prepared.digest,
                  "Authenticated provider history page failed schema, scope or progress checks");
          });
      return Optional.empty();
    }
    Boolean complete = tx.execute(s -> accept(claim, prepared, page));
    return Boolean.TRUE.equals(complete) ? load(prepared.task, summary) : Optional.empty();
  }

  private Prepared prepare(Map<String, Object> claim, Map<String, Object> summary) {
    if (!fence(claim)) return null;
    var control = db.one("select * from system_control where id=true");
    UUID merchant = Db.uuid(claim, "merchant_id");
    validateSummary(claim, summary, control);
    String digest = CanonicalJson.sha256(summary);
    validateInboxOrigin(claim, summary);
    UUID resource = UUID.fromString(summary.get("history_resource_id").toString());
    long high = integer(summary.get("source_high_water")),
        expected = integer(summary.get("history_source_count"));
    require(
        high >= 0 && expected >= 0 && integer(summary.get("source_count")) == expected,
        409,
        "Provider history snapshot count mismatch");
    UUID newId = UUID.randomUUID();
    db.exec(
        "insert into"
            + " provider_history_task(task_id,operation_id,inbox_id,merchant_id,summary_json,summary_digest,history_resource_id,source_high_water,expected_count)"
            + " values (?,?,?,?,?,?,?,?,?) on conflict do nothing",
        newId,
        isInbox(claim) ? null : claim.get("id"),
        isInbox(claim) ? claim.get("id") : null,
        merchant,
        CanonicalJson.string(summary),
        digest,
        resource,
        high,
        expected);
    var task = task(claim, true).orElseThrow();
    UUID id = Db.uuid(task, "task_id");
    if (!digest.equals(task.get("summary_digest"))) {
      if (!task.get("status").equals("COMPLETE"))
        manualTask(id, digest, "Immutable provider result summary changed");
      else quarantine(digest, "Immutable completed provider history summary changed");
      return null;
    }
    if (expected > MAX_SOURCES) {
      manualTask(
          id,
          digest,
          "Provider history exceeds the 10000-source bound; original financial obligation retained"
              + " for operator recovery");
      return null;
    }
    if (task.get("status").equals("MANUAL")) return null;
    boolean complete = task.get("status").equals("COMPLETE");
    if (!complete)
      db.exec(
          "update provider_history_task set"
              + " owner=?,generation=?,run_id=?,dispatch_epoch=?,continuation_deadline=clock_timestamp()+interval"
              + " '10 seconds',updated_at=clock_timestamp() where task_id=?",
          claim.get("owner"),
          claim.get("generation"),
          claim.get("run_id"),
          claim.get("epoch"),
          id);
    return new Prepared(
        id, merchant, resource, digest, high, expected, Db.number(task, "cursor"), complete);
  }

  /** Authenticated callback header check only: no provider call and no source application. */
  public void validateSummary(Map<String, Object> operation, Map<String, Object> summary) {
    validateSummary(operation, summary, db.one("select * from system_control where id=true"));
  }

  private void validateSummary(
      Map<String, Object> claim, Map<String, Object> summary, Map<String, Object> control) {
    require(
        CanonicalJson.bytes(summary).length <= 128 * 1024,
        409,
        "Provider history summary exceeds its fixed bound");
    require(
        integer(summary.get("schema_version")) == 1
            && "mprp_v1".equals(summary.get("policy_version"))
            && "USD".equals(summary.get("currency"))
            && control.get("environment_id").toString().equals(summary.get("environment_id"))
            && control.get("provider_instance_id") != null
            && control
                .get("provider_instance_id")
                .toString()
                .equals(summary.get("simulator_instance_id"))
            && claim.get("merchant_id").toString().equals(summary.get("provider_account_id"))
            && claim.get("provider_key").equals(summary.get("operation_key"))
            && claim.get("fingerprint").equals(summary.get("payload_hash"))
            && claim.get("resource_id").toString().equals(summary.get("resource_id"))
            && Set.of("SUCCEEDED", "FAILED", "NO_EFFECT").contains(summary.get("state")),
        409,
        "Provider history summary identity mismatch");
    require(
        summary.get("sources") instanceof List<?> list && list.isEmpty(),
        409,
        "Bounded provider summary must not contain partial source history");
    UUID resource = UUID.fromString(summary.get("history_resource_id").toString());
    boolean scope =
        resource.toString().equals(summary.get("resource_id"))
            || resource.toString().equals(summary.get("batch_id"));
    if (summary.get("batches") instanceof List<?> batches) {
      require(batches.size() <= 100, 409, "Too many batch metadata records");
      for (Object raw : batches) {
        require(raw instanceof Map<?, ?>, 409, "Invalid batch metadata");
        var batch = (Map<?, ?>) raw;
        require(
            !batch.containsKey("sources") && !batch.containsKey("details"),
            409,
            "Partial batch details in summary");
        if (resource.toString().equals(batch.get("batch_id"))) scope = true;
      }
    }
    require(scope, 409, "History resource does not identify the operation or its frozen batch");
  }

  @SuppressWarnings("unchecked")
  private PageData validatePage(Prepared prepared, Map<String, Object> response) {
    require(
        integer(response.get("schema_version")) == 1
            && "mprp_v1".equals(response.get("policy_version"))
            && integer(response.get("snapshot")) == prepared.highWater
            && integer(response.get("source_high_water")) == prepared.highWater
            && integer(response.get("after")) == prepared.cursor
            && response.get("complete") instanceof Boolean
            && response.get("sources") instanceof List<?>,
        409,
        "History page snapshot identity mismatch");
    List<?> raw = (List<?>) response.get("sources");
    require(raw.size() <= 250, 409, "Provider history page exceeds250sources");
    List<Map<String, Object>> sources = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    long last = prepared.cursor;
    for (Object value : raw) {
      require(value instanceof Map<?, ?>, 409, "Invalid source history item");
      Map<String, Object> source = (Map<String, Object>) value;
      long sequence = integer(source.get("sequence"));
      String id = Objects.toString(source.get("source_id"), ""),
          type = Objects.toString(source.get("event_type"), "");
      require(
          sequence > last
              && sequence <= prepared.highWater
              && id.matches("[A-Za-z0-9_.-]{1,128}")
              && seen.add(id + ":" + type)
              && Set.of(
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
                  .contains(type)
              && prepared.merchant.toString().equals(source.get("provider_account_id"))
              && "USD".equals(source.get("currency"))
              && source.get("clearing_line") instanceof Boolean,
          409,
          "History source scope, identity or order mismatch");
      UUID.fromString(source.get("resource_id").toString());
      Instant.parse(source.get("occurred_at").toString());
      require(
          prepared.resource.toString().equals(source.get("resource_id"))
              || prepared.resource.toString().equals(source.get("payment_id"))
              || prepared.resource.toString().equals(source.get("batch_id")),
          409,
          "Foreign aggregate source in history page");
      long amount = integer(source.get("amount_minor"));
      require(
          amount != Long.MIN_VALUE && Math.abs(amount) <= 100000000000000L,
          409,
          "History source amount bound");
      require(
          CanonicalJson.bytes(source).length <= 16384,
          409,
          "History source representation too large");
      sources.add(source);
      last = sequence;
    }
    long next = integer(response.get("next_after"));
    boolean complete = Boolean.TRUE.equals(response.get("complete"));
    require(
        next == last && (complete || (!sources.isEmpty() && next > prepared.cursor)),
        409,
        "History page made no monotonic progress");
    return new PageData(sources, next, complete);
  }

  private boolean accept(Map<String, Object> claim, Prepared prepared, PageData page) {
    if (!fence(claim)) return false;
    var task =
        db.one(
            "select *,continuation_deadline>clock_timestamp() as within_deadline from"
                + " provider_history_task where task_id=? for update",
            prepared.task);
    if (task.get("status").equals("COMPLETE"))
      return prepared.digest.equals(task.get("summary_digest"));
    if (task.get("status").equals("MANUAL") || Db.number(task, "cursor") != prepared.cursor)
      return false;
    require(
        prepared.digest.equals(task.get("summary_digest"))
            && claim.get("owner").equals(task.get("owner"))
            && integer(claim.get("generation")) == Db.number(task, "generation")
            && claim.get("run_id").equals(task.get("run_id"))
            && integer(claim.get("epoch")) == Db.number(task, "dispatch_epoch"),
        409,
        "Stale history continuation owner");
    if (!Boolean.TRUE.equals(task.get("within_deadline"))) {
      db.exec(
          "update provider_history_task set safe_reason='Page continuation deadline elapsed;"
              + " retained cursor will resume',updated_at=clock_timestamp() where task_id=?",
          prepared.task);
      return false;
    }
    var retained =
        db.query(
            "select source_id,event_type,sequence,digest from provider_history_source where"
                + " task_id=? limit 10001",
            prepared.task);
    if (retained.size() > MAX_SOURCES || retained.size() != Db.number(task, "source_count")) {
      manualTask(
          prepared.task,
          prepared.digest,
          "Retained history source count changed outside its continuation");
      return false;
    }
    Map<String, Map<String, Object>> identities = new HashMap<>();
    Set<Long> sequences = new HashSet<>();
    for (var row : retained) {
      identities.put(row.get("source_id") + ":" + row.get("event_type"), row);
      sequences.add(Db.number(row, "sequence"));
    }
    List<Object[]> additions = new ArrayList<>();
    for (var source : page.sources) {
      String id = source.get("source_id").toString(),
          type = source.get("event_type").toString(),
          hash = CanonicalJson.sha256(source);
      long sequence = integer(source.get("sequence"));
      var prior = identities.get(id + ":" + type);
      if (prior != null) {
        if (Db.number(prior, "sequence") != sequence || !hash.equals(prior.get("digest"))) {
          manualTask(
              prepared.task,
              prepared.digest,
              "Provider source identity changed within the frozen history snapshot");
          return false;
        }
      } else {
        if (sequences.contains(sequence)) {
          manualTask(
              prepared.task, prepared.digest, "Provider history sequence changed source identity");
          return false;
        }
        additions.add(
            new Object[] {
              prepared.task,
              prepared.merchant,
              id,
              type,
              sequence,
              CanonicalJson.string(source),
              hash
            });
      }
    }
    long count = Math.addExact(Db.number(task, "source_count"), additions.size());
    if (count > prepared.expected
        || count > MAX_SOURCES
        || (page.complete && count != prepared.expected)) {
      manualTask(
          prepared.task,
          prepared.digest,
          "Complete provider history disagrees with its frozen source count");
      return false;
    }
    if (!additions.isEmpty())
      db.jdbc.batchUpdate(
          "insert into"
              + " provider_history_source(task_id,merchant_id,source_id,event_type,sequence,source_json,digest)"
              + " values (?,?,?,?,?,?,?)",
          additions);
    require(
        db.scalar(
            "select continuation_deadline>clock_timestamp() from provider_history_task where"
                + " task_id=?",
            Boolean.class,
            prepared.task),
        503,
        "History page exceeded its bounded continuation deadline");
    String digest = null;
    Integer postingTotal = null;
    if (page.complete) {
      StringBuilder hashes = new StringBuilder();
      int economic = 0;
      for (var row :
          db.query(
              "select digest,event_type from provider_history_source where task_id=? order by"
                  + " sequence",
              prepared.task)) {
        hashes.append(row.get("digest"));
        if (!TRANSFERS.contains(row.get("event_type"))) economic++;
      }
      digest =
          CanonicalJson.digest(
              hashes.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      postingTotal = economic;
    }
    db.exec(
        "update provider_history_task set"
            + " cursor=?,source_count=?,status=?,safe_reason=?,sources_digest=coalesce(sources_digest,?),posting_total=coalesce(posting_total,?),posting_complete=posting_complete"
            + " OR ?,updated_at=clock_timestamp() where task_id=?",
        page.next,
        count,
        page.complete ? "COMPLETE" : "FETCHING",
        page.complete
            ? "Authenticated frozen source history complete"
            : "Authenticated history page retained; continuation required",
        digest,
        postingTotal,
        page.complete && postingTotal != null && postingTotal == 0,
        prepared.task);
    db.exec(
        "update durable_job set last_error='Authenticated history page staged' where id=?",
        claim.get("job_id"));
    require(fence(claim), 409, "History claim expired before staging commitment");
    return page.complete;
  }

  /**
   * Immutable cache inspection; callers retain their own current human/system disclosure authority.
   */
  public Optional<Map<String, Object>> completed(UUID operation, Map<String, Object> summary) {
    return completedOrigin(operation, false, summary);
  }

  public Optional<Map<String, Object>> completedInbox(UUID inbox, Map<String, Object> summary) {
    return completedOrigin(inbox, true, summary);
  }

  public Optional<Map<String, Object>> cachedSummary(UUID operation) {
    return db.maybe(
            "select summary_json,summary_digest from provider_history_task where operation_id=?",
            operation)
        .map(
            row -> {
              var summary = CanonicalJson.parse(row.get("summary_json").toString());
              require(
                  CanonicalJson.sha256(summary).equals(row.get("summary_digest")),
                  409,
                  "Retained provider summary integrity failed");
              return summary;
            });
  }

  private Optional<Map<String, Object>> completedOrigin(
      UUID identity, boolean inbox, Map<String, Object> summary) {
    var row =
        db.maybe(
            "select task_id from provider_history_task where "
                + (inbox ? "inbox_id" : "operation_id")
                + "=? and status='COMPLETE' and summary_digest=?",
            identity,
            CanonicalJson.sha256(summary));
    return row.isEmpty() ? Optional.empty() : load(Db.uuid(row.get(), "task_id"), summary);
  }

  @SuppressWarnings("unchecked")
  private Optional<Map<String, Object>> load(UUID id, Map<String, Object> summary) {
    var found =
        db.maybe(
            "select * from provider_history_task where task_id=? and status='COMPLETE' and"
                + " summary_digest=?",
            id,
            CanonicalJson.sha256(summary));
    if (found.isEmpty()) return Optional.empty();
    var row = found.get();
    var result =
        new LinkedHashMap<String, Object>(CanonicalJson.parse(row.get("summary_json").toString()));
    require(
        CanonicalJson.sha256(result).equals(row.get("summary_digest")),
        409,
        "Retained history summary integrity failed");
    List<Map<String, Object>> sources = new ArrayList<>();
    if (Boolean.TRUE.equals(row.get("inline_sources")))
      sources.addAll((List<Map<String, Object>>) result.get("sources"));
    else
      for (var record :
          db.query(
              "select source_json,digest from provider_history_source where task_id=? order by"
                  + " sequence",
              id)) {
        var source = CanonicalJson.parse(record.get("source_json").toString());
        require(
            CanonicalJson.sha256(source).equals(record.get("digest")),
            409,
            "Retained history source integrity failed");
        sources.add(source);
      }
    require(
        sources.size() == Db.number(row, "expected_count")
            && sources.size() <= MAX_SOURCES
            && sourceDigest(sources).equals(row.get("sources_digest")),
        409,
        "Retained complete history proof mismatch");
    result.put("sources", sources);
    result.put("history_required", false);
    if (result.get("batches") instanceof List<?> batches) {
      List<Map<String, Object>> hydrated = new ArrayList<>();
      for (Object raw : batches) {
        var batch = new LinkedHashMap<String, Object>((Map<String, Object>) raw);
        String batchId = Objects.toString(batch.get("batch_id"), "");
        var records =
            sources.stream().filter(source -> batchId.equals(source.get("batch_id"))).toList();
        batch.put("sources", records);
        batch.put(
            "details",
            records.stream()
                .filter(source -> Boolean.TRUE.equals(source.get("clearing_line")))
                .toList());
        hydrated.add(batch);
      }
      result.put("batches", hydrated);
    }
    if (result.containsKey("batch_id"))
      result.put(
          "details",
          sources.stream()
              .filter(source -> Boolean.TRUE.equals(source.get("clearing_line")))
              .toList());
    return Optional.of(result);
  }

  /** Validates the complete immutable snapshot before posting at most100 nontransfer journals. */
  @SuppressWarnings("unchecked")
  public boolean postChunk(
      Map<String, Object> claim, Map<String, Object> result, LedgerService ledger) {
    require(
        !TransactionSynchronizationManager.isActualTransactionActive(),
        409,
        "History validation must precede the journal transaction");
    UUID merchant = Db.uuid(claim, "merchant_id");
    Map<String, Object> retainedOperation =
        isInbox(claim)
            ? db.maybe(
                    "select * from operation where provider_key=? and merchant_id=?",
                    claim.get("provider_key"),
                    merchant)
                .orElse(null)
            : claim;
    validator.validate(merchant, retainedOperation, result);
    List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");
    String proof = sourceDigest(sources);
    List<Map<String, Object>> economic =
        sources.stream().filter(source -> !TRANSFERS.contains(source.get("event_type"))).toList();
    Boolean complete =
        tx.execute(
            s -> {
              db.lockMerchant(merchant);
              if (!fence(claim)) return false;
              var found = task(claim, true);
              if (found.isEmpty()) {
                require(
                    CanonicalJson.bytes(result).length <= 128 * 1024,
                    409,
                    "Unstaged provider result exceeds inline source bound");
                validateInboxOrigin(claim, result);
                UUID id = UUID.randomUUID();
                long high = sources.isEmpty() ? 0 : integer(sources.getLast().get("sequence"));
                UUID resource =
                    UUID.fromString(
                        result.getOrDefault("batch_id", result.get("resource_id")).toString());
                db.exec(
                    "insert into"
                        + " provider_history_task(task_id,operation_id,inbox_id,merchant_id,summary_json,summary_digest,history_resource_id,source_high_water,expected_count,cursor,source_count,status,inline_sources,sources_digest,posting_total,posting_complete)"
                        + " values (?,?,?,?,?,?,?,?,?,?,?,'COMPLETE',true,?,?,?)",
                    id,
                    isInbox(claim) ? null : claim.get("id"),
                    isInbox(claim) ? claim.get("id") : null,
                    merchant,
                    CanonicalJson.string(result),
                    CanonicalJson.sha256(result),
                    resource,
                    high,
                    sources.size(),
                    high,
                    sources.size(),
                    proof,
                    economic.size(),
                    economic.isEmpty());
                found = task(claim, true);
              }
              var task = found.orElseThrow();
              UUID taskId = Db.uuid(task, "task_id");
              require(
                  task.get("status").equals("COMPLETE")
                      && proof.equals(task.get("sources_digest"))
                      && economic.size() == Db.number(task, "posting_total"),
                  409,
                  "Unverified or changed history cannot post journals");
              int posted = Math.toIntExact(Db.number(task, "posted_count"));
              if (posted == economic.size()) return true;
              int hardEnd = Math.min(posted + 100, economic.size()), end = posted;
              long budget = System.nanoTime() + 8_000_000_000L;
              while (end < hardEnd) {
                if (end + 1 == hardEnd
                    && end + 1 < economic.size()
                    && "CAPTURE".equals(economic.get(end).get("event_type"))
                    && "FEE".equals(economic.get(end + 1).get("event_type"))) break;
                ledger.apply(
                    merchant,
                    retainedOperation == null ? null : Db.uuid(retainedOperation, "id"),
                    List.of(economic.get(end)));
                end++;
                if (System.nanoTime() >= budget
                    && !"CAPTURE".equals(economic.get(end - 1).get("event_type"))) break;
              }
              require(end > posted, 503, "History journal chunk made no progress");
              require(fence(claim), 409, "History journal claim expired before commitment");
              db.exec(
                  "update provider_history_task set"
                      + " posted_count=?,posting_complete=?,safe_reason='Authenticated economic"
                      + " sources applied in bounded chunks',updated_at=clock_timestamp() where"
                      + " task_id=?",
                  end,
                  end == economic.size(),
                  taskId);
              // The deferred durable-job guard rechecks the OLD lease at the real journal commit.
              db.exec(
                  "update durable_job set last_error='Authenticated history journal progress' where"
                      + " id=?",
                  claim.get("job_id"));
              return end == economic.size();
            });
    return Boolean.TRUE.equals(complete);
  }

  public boolean manual(UUID operation) {
    return manualOrigin(operation, false);
  }

  public boolean manualInbox(UUID inbox) {
    return manualOrigin(inbox, true);
  }

  private boolean manualOrigin(UUID identity, boolean inbox) {
    return db.scalar(
            "select count(*) from provider_history_task where "
                + (inbox ? "inbox_id" : "operation_id")
                + "=? and status='MANUAL'",
            Long.class,
            identity)
        > 0;
  }

  public String message(UUID operation) {
    return messageOrigin(operation, false);
  }

  public String messageInbox(UUID inbox) {
    return messageOrigin(inbox, true);
  }

  private String messageOrigin(UUID identity, boolean inbox) {
    return db.maybe(
            "select safe_reason from provider_history_task where "
                + (inbox ? "inbox_id" : "operation_id")
                + "=?",
            identity)
        .map(r -> r.get("safe_reason").toString())
        .orElse("Provider history continuation pending");
  }

  public boolean preposted(UUID operation) {
    return prepostedOrigin(operation, false);
  }

  public boolean prepostedInbox(UUID inbox) {
    return prepostedOrigin(inbox, true);
  }

  private boolean prepostedOrigin(UUID identity, boolean inbox) {
    return db.scalar(
            "select count(*) from provider_history_task where "
                + (inbox ? "inbox_id" : "operation_id")
                + "=? and status='COMPLETE' and posted_count=posting_total",
            Long.class,
            identity)
        > 0;
  }

  @Transactional
  public void redrive(UUID operation) {
    redriveOrigin(operation, false);
  }

  @Transactional
  public void redriveInbox(UUID inbox) {
    redriveOrigin(inbox, true);
  }

  @Transactional
  public void redriveTask(UUID taskId) {
    auth.platform("OPERATIONS");
    var row =
        db.one("select operation_id,inbox_id from provider_history_task where task_id=?", taskId);
    redriveOrigin(
        Db.uuid(row, row.get("inbox_id") == null ? "operation_id" : "inbox_id"),
        row.get("inbox_id") != null);
  }

  private void redriveOrigin(UUID identity, boolean inbox) {
    UUID actor = auth.platform("OPERATIONS");
    auth.stepUp();
    var task =
        db.one(
            "select * from provider_history_task where "
                + (inbox ? "inbox_id" : "operation_id")
                + "=?",
            identity);
    UUID merchant = Db.uuid(task, "merchant_id");
    db.lockMerchant(merchant);
    db.one("select id from system_control where id=true for update");
    task =
        db.one(
            "select * from provider_history_task where task_id=? for update", task.get("task_id"));
    require(
        Db.number(task, "expected_count") <= MAX_SOURCES,
        409,
        "Retained history exceeds the supported source bound; recovery inventory is required");
    db.exec(
        "update provider_history_task set status='FETCHING',safe_reason='Staff-authorized"
            + " continuation of original provider history' where task_id=? and status='MANUAL'",
        task.get("task_id"));
    String jobScope = inbox ? "kind='PROVIDER_HISTORY' and resource_id=?" : "operation_id=?";
    int woken =
        db.exec(
            "update durable_job set"
                + " state='READY',generation=generation+1,lease_until=null,due_at=clock_timestamp(),last_error='Staff"
                + " authorized retained history retry' where "
                + jobScope
                + " and state='MANUAL'",
            identity);
    require(
        woken == 1,
        409,
        "Retained history work is already active or completed; inspect its current state");
    audit.record(
        merchant,
        actor,
        "provider.history.redriven",
        identity,
        "Original provider identity, cursor and fixed source high-water retained");
  }

  public boolean isCurrentClaim(Map<String, Object> claim) {
    require(
        TransactionSynchronizationManager.isActualTransactionActive(),
        409,
        "Lease inspection requires its write transaction");
    return fence(claim);
  }

  private boolean fence(Map<String, Object> claim) {
    require(
        claim.get("owner") instanceof UUID,
        409,
        "History claim requires its committed owner identity");
    db.lockMerchant(Db.uuid(claim, "merchant_id"));
    db.one("select id from system_control where id=true for update");
    String origin =
        isInbox(claim)
            ? "j.operation_id is null and j.kind='PROVIDER_HISTORY' and j.resource_id=?"
            : "j.operation_id=?";
    boolean current =
        db.maybe(
                "select j.id from durable_job j join system_control c on c.id=true where j.id=? and"
                    + " "
                    + origin
                    + " and j.merchant_id=? and j.owner=? and j.generation=? and j.run_id=? and"
                    + " j.dispatch_epoch=? and j.run_id=c.run_id and"
                    + " j.dispatch_epoch=c.dispatch_epoch and j.lease_until>clock_timestamp() and"
                    + " j.state='RUNNING' for update of j",
                claim.get("job_id"),
                claim.get("id"),
                claim.get("merchant_id"),
                claim.get("owner"),
                claim.get("generation"),
                claim.get("run_id"),
                claim.get("epoch"))
            .isPresent();
    if (current)
      db.exec("update durable_job set last_error=last_error where id=?", claim.get("job_id"));
    return current;
  }

  private Optional<Map<String, Object>> task(Map<String, Object> claim, boolean lock) {
    return db.maybe(
        "select * from provider_history_task where "
            + (isInbox(claim) ? "inbox_id" : "operation_id")
            + "=?"
            + (lock ? " for update" : ""),
        claim.get("id"));
  }

  private static boolean isInbox(Map<String, Object> claim) {
    return claim.get("history_inbox_id") != null;
  }

  @SuppressWarnings("unchecked")
  private void validateInboxOrigin(Map<String, Object> claim, Map<String, Object> summary) {
    if (!isInbox(claim)) return;
    var row = db.one("select payload from callback_inbox where id=?", claim.get("id"));
    var event = CanonicalJson.parse(row.get("payload").toString());
    require(
        event.get("result") instanceof Map<?, ?>
            && CanonicalJson.sha256((Map<String, Object>) event.get("result"))
                .equals(CanonicalJson.sha256(summary)),
        409,
        "History differs from its authenticated inbox origin");
    var operation =
        db.maybe(
            "select * from operation where provider_key=? and merchant_id=?",
            summary.get("operation_key"),
            claim.get("merchant_id"));
    if (operation.isPresent()) {
      var op = operation.get();
      require(
          op.get("fingerprint").equals(summary.get("payload_hash"))
              && op.get("resource_id").toString().equals(summary.get("resource_id")),
          409,
          "Retained operation and callback history identity mismatch");
    } else
      require(
          Set.of("settlement_completed", "dispute_outcome").contains(event.get("event_type")),
          409,
          "History has no retained admission or supported system-event authority");
  }

  private static String sourceDigest(List<Map<String, Object>> sources) {
    StringBuilder joined = new StringBuilder();
    for (var source : sources) joined.append(CanonicalJson.sha256(source));
    return CanonicalJson.digest(
        joined.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  private void manualTask(UUID task, String digest, String reason) {
    db.exec(
        "update provider_history_task set"
            + " status='MANUAL',safe_reason=?,updated_at=clock_timestamp() where task_id=? and"
            + " status<>'COMPLETE'",
        reason,
        task);
    quarantine(digest, reason);
  }

  private void quarantine(String digest, String reason) {
    db.exec(
        "insert into quarantine(id,kind,digest,safe_reason) values (?,'PROVIDER_HISTORY',?,?)",
        UUID.randomUUID(),
        digest,
        reason);
  }

  private static long integer(Object value) {
    require(
        value instanceof Byte
            || value instanceof Short
            || value instanceof Integer
            || value instanceof Long
            || value instanceof java.math.BigInteger,
        409,
        "Exact integer provider history field required");
    return value instanceof java.math.BigInteger n
        ? n.longValueExact()
        : ((Number) value).longValue();
  }
}
