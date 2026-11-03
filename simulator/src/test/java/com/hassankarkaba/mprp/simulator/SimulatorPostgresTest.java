package com.hassankarkaba.mprp.simulator;

import static org.junit.jupiter.api.Assertions.*;

import com.hassankarkaba.mprp.protocol.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs only against an explicitly supplied isolated PostgreSQL, never an implicit developer
 * database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "TEST_SIMULATOR_DB_URL", matches = "jdbc:postgresql:.*")
@Timeout(180)
class SimulatorPostgresTest {
  @org.springframework.beans.factory.annotation.Autowired ProviderStore provider;
  static final UUID ENV = UUID.randomUUID(), MAIN = UUID.randomUUID();
  static final String SECRET = "isolated-integration-test-service-secret";
  @LocalServerPort int port;
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("TEST_SIMULATOR_DB_URL"));
    r.add("spring.datasource.username", () -> "simulator_runtime");
    r.add("spring.datasource.password", () -> System.getenv("TEST_SIMULATOR_DB_PASSWORD"));
    r.add("spring.flyway.enabled", () -> true);
    r.add("spring.flyway.user", () -> "simulator_owner");
    r.add("spring.flyway.password", () -> System.getenv("TEST_SIMULATOR_OWNER_PASSWORD"));
    r.add("mprp.service-key-active-from", () -> Instant.now().minusSeconds(60).toString());
    r.add("mprp.service-key-expires-at", () -> Instant.now().plus(Duration.ofDays(365)).toString());
    r.add("mprp.environment-id", () -> ENV.toString());
    r.add("mprp.service-secret", () -> SECRET);
    r.add("mprp.key-id", () -> "local-v1");
    r.add("mprp.worker", () -> false);
    r.add("mprp.callback-origin", () -> "http://localhost:8180");
  }

  Map<String, Object> envelope(
      UUID account, UUID resource, String key, Map<String, Object> payload) {
    Map<String, Object> e = new HashMap<>();
    e.put("schema_version", 1);
    e.put("policy_version", "mprp_v1");
    e.put("environment_id", ENV.toString());
    e.put("main_instance_id", MAIN.toString());
    e.put("dispatch_epoch", 1);
    e.put("provider_account_id", account.toString());
    e.put("resource_id", resource.toString());
    e.put("operation_key", key);
    e.put("payload", payload);
    e.put("payload_hash", CanonicalJson.sha256(payload));
    return e;
  }

  HttpResponse<String> send(String method, String path, byte[] bytes, Map<String, String> headers)
      throws Exception {
    HttpRequest.Builder r =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json");
    headers.forEach(r::header);
    r.method(
        method,
        bytes.length == 0
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(bytes));
    return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> send(String method, String path, Object body) throws Exception {
    byte[] bytes = body == null ? new byte[0] : CanonicalJson.bytes(body);
    return send(method, path, bytes, HmacAuth.headers(SECRET, "local-v1", method, path, bytes));
  }

  HttpResponse<String> operatorPost(String path, Object body, String purgeSecret) throws Exception {
    byte[] bytes = CanonicalJson.bytes(body);
    var headers = new HashMap<>(HmacAuth.headers(SECRET, "local-v1", "POST", path, bytes));
    headers.put("X-MPRP-Purge-Secret", purgeSecret);
    return send("POST", path, bytes, headers);
  }

  HttpResponse<String> retireWithOriginalRequest(String path, Object body, String purgeSecret)
      throws Exception {
    java.net.http.HttpTimeoutException uncertainty = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        var response = operatorPost(path, body, purgeSecret);
        if (response.statusCode() == 200 || uncertainty == null || response.statusCode() < 500)
          return response;
      } catch (java.net.http.HttpTimeoutException timeout) {
        uncertainty = timeout;
        System.out.println(
            "Operator retirement response uncertain; replaying the unchanged approval and"
                + " inventory");
      }
      if (attempt < 2) Thread.sleep(200);
    }
    throw uncertainty;
  }

  Map<String, Object> post(String path, Object body) throws Exception {
    HttpResponse<String> r = send("POST", path, body);
    assertEquals(200, r.statusCode(), r.body());
    return CanonicalJson.parse(r.body());
  }

  @BeforeEach
  void handshake() throws Exception {
    post(
        "/v1/control/handshake/",
        Map.of(
            "environment_id",
            ENV.toString(),
            "main_instance_id",
            MAIN.toString(),
            "dispatch_epoch",
            1,
            "run_token",
            MAIN.toString()));
  }

  @Test
  void finalPartialCaptureAndUnrefundedFeeCommitOnce() throws Exception {
    UUID a = UUID.randomUUID(), p = UUID.randomUUID();
    String auth = UUID.randomUUID().toString(), capture = UUID.randomUUID().toString();
    var authorization =
        envelope(
            a,
            p,
            auth,
            Map.of("amount_minor", 10000, "token", "tok_demo_success", "capture_mode", "MANUAL"));
    assertEquals("AUTHORIZED", post("/v1/authorize/", authorization).get("business_state"));
    var request = envelope(a, p, capture, Map.of("amount_minor", 4000));
    var result = post("/v1/capture/", request);
    assertEquals(146, ((Number) result.get("fee_minor")).intValue());
    assertEquals(CanonicalJson.string(result), CanonicalJson.string(post("/v1/capture/", request)));
    var second =
        post(
            "/v1/capture/",
            envelope(a, p, UUID.randomUUID().toString(), Map.of("amount_minor", 1000)));
    assertEquals("FAILED", second.get("state"));
    assertEquals(List.of(), second.get("sources"));
    var refund =
        post(
            "/v1/refund/",
            envelope(
                a,
                p,
                UUID.randomUUID().toString(),
                Map.of("amount_minor", 4000, "reason", "Fictional return")));
    assertEquals(4000, ((Number) refund.get("refunded_minor")).intValue());
    assertEquals(146, ((Number) refund.get("fee_minor")).intValue());
    var batch =
        post(
            "/v1/flush/",
            envelope(
                a,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                Map.of("cutoff_identity", UUID.randomUUID().toString())));
    assertEquals(-146, ((Number) batch.get("net_minor")).intValue());
  }

  @Test
  void independentlyCommittedTimeoutResolvesOriginalSources() throws Exception {
    UUID a = UUID.randomUUID(), p = UUID.randomUUID();
    var request =
        envelope(
            a,
            p,
            UUID.randomUUID().toString(),
            Map.of(
                "amount_minor",
                10000,
                "token",
                "tok_demo_timeout_after_commit",
                "capture_mode",
                "AUTOMATIC"));
    assertEquals(503, send("POST", "/v1/authorize/", request).statusCode());
    var resolved = post("/v1/resolve-key/", request);
    assertEquals("SUCCEEDED", resolved.get("state"));
    assertEquals("CAPTURED", resolved.get("business_state"));
    assertEquals(320, ((Number) resolved.get("fee_minor")).intValue());
    assertEquals(
        CanonicalJson.string(resolved), CanonicalJson.string(post("/v1/resolve-key/", request)));
  }

  @Test
  void resolverTombstonePermanentlyPreventsDelayedMutation() throws Exception {
    UUID a = UUID.randomUUID(), p = UUID.randomUUID();
    var request =
        envelope(
            a,
            p,
            UUID.randomUUID().toString(),
            Map.of(
                "amount_minor", 10000, "token", "tok_demo_success", "capture_mode", "AUTOMATIC"));
    assertEquals("NO_EFFECT", post("/v1/resolve-key/", request).get("state"));
    assertEquals("NO_EFFECT", post("/v1/authorize/", request).get("state"));
    assertEquals(404, send("GET", "/v1/payments/" + p + "/", null).statusCode());
  }

  @Test
  void serviceNonceReplayAndChangedPayloadCannotExecute() throws Exception {
    UUID a = UUID.randomUUID(), p = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    var request =
        envelope(
            a,
            p,
            key,
            Map.of("amount_minor", 10000, "token", "tok_demo_success", "capture_mode", "MANUAL"));
    byte[] bytes = CanonicalJson.bytes(request);
    var headers = HmacAuth.headers(SECRET, "local-v1", "POST", "/v1/authorize/", bytes);
    assertEquals(200, send("POST", "/v1/authorize/", bytes, headers).statusCode());
    assertEquals(401, send("POST", "/v1/authorize/", bytes, headers).statusCode());
    assertEquals(
        409,
        send(
                "POST",
                "/v1/authorize/",
                envelope(
                    a,
                    p,
                    key,
                    Map.of(
                        "amount_minor",
                        9000,
                        "token",
                        "tok_demo_success",
                        "capture_mode",
                        "MANUAL")))
            .statusCode());
  }

  @Test
  void zeroBatchHasAuthenticatedZeroEvidenceAndNoBankLine() throws Exception {
    UUID a = UUID.randomUUID();
    post("/v1/accounts/", envelope(a, a, UUID.randomUUID().toString(), Map.of()));
    var b =
        post(
            "/v1/flush/",
            envelope(
                a,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                Map.of("cutoff_identity", UUID.randomUUID().toString())));
    assertEquals(0, ((Number) b.get("net_minor")).intValue());
    @SuppressWarnings("unchecked")
    var sources = (List<Map<String, Object>>) b.get("sources");
    assertEquals(1, sources.size());
    assertEquals("BATCH_ZERO", sources.getFirst().get("event_type"));
    assertEquals(b.get("batch_id"), sources.getFirst().get("source_id"));
    assertEquals(0, ((Number) sources.getFirst().get("amount_minor")).intValue());
    String start = CanonicalJson.instant(Instant.now().minusSeconds(60)),
        end = CanonicalJson.instant(Instant.now().plusSeconds(60));
    var transfer =
        post(
            "/v1/statements/",
            Map.of(
                "provider_account_id",
                a.toString(),
                "kind",
                "PROCESSOR_TRANSFER",
                "period_start",
                start,
                "period_end",
                end));
    assertEquals(1, ((Number) transfer.get("row_count")).intValue());
    var bank =
        post(
            "/v1/statements/",
            Map.of(
                "provider_account_id",
                a.toString(),
                "kind",
                "BANK",
                "period_start",
                start,
                "period_end",
                end));
    assertEquals(0, ((Number) bank.get("row_count")).intValue());
  }

  @Test
  void inventoryBeyondOnePageIsFrozenAndStaleReleaseIsRejected() throws Exception {
    UUID a = UUID.randomUUID(), first = UUID.randomUUID();
    post("/v1/accounts/", envelope(a, a, UUID.randomUUID().toString(), Map.of()));
    // Bulk fixture ownership is explicit. Signed HTTP below exercises inventory, not workload
    // capacity.
    var data =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            System.getenv("TEST_SIMULATOR_DB_URL"),
            "simulator_owner",
            System.getenv("TEST_SIMULATOR_OWNER_PASSWORD"));
    var fixture = new org.springframework.jdbc.core.JdbcTemplate(data);
    fixture.setQueryTimeout(30);
    var fixtureTx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(data));
    fixtureTx.executeWithoutResult(
        t -> {
          long sequence =
              fixture.queryForObject(
                  "SELECT source_sequence FROM sim_control WHERE id=1 FOR UPDATE", Long.class);
          UUID batch = UUID.randomUUID();
          fixture.update(
              "INSERT INTO sim_batches(id,account_id,state) VALUES(?,?,'OPEN')", batch, a);
          for (int i = 0; i < 260; i++) {
            UUID payment = i == 0 ? first : UUID.randomUUID(),
                capture = UUID.randomUUID(),
                fee = UUID.randomUUID();
            String key = UUID.randomUUID().toString();
            fixture.update(
                "INSERT INTO"
                    + " sim_payments(id,account_id,authorized_minor,captured_minor,fee_minor,state,authorization_expires_at)"
                    + " VALUES(?,?,100,100,33,'CAPTURED',now()+interval '7 days')",
                payment,
                a);
            fixture.update(
                "INSERT INTO"
                    + " sim_sources(source_id,sequence,account_id,resource_id,payment_id,event_type,amount_minor,occurred_at,reference,batch_id)"
                    + " VALUES(?,?,?,?,?,'CAPTURE',100,now(),?,?),(?,?,?,?,?,'FEE',-33,now(),?,?)",
                capture,
                ++sequence,
                a,
                payment,
                payment,
                capture.toString(),
                batch,
                fee,
                ++sequence,
                a,
                payment,
                payment,
                fee.toString(),
                batch);
            fixture.update(
                "INSERT INTO"
                    + " sim_operations(operation_key,payload_hash,kind,account_id,resource_id,state,payload,result)"
                    + " VALUES(?,?,'authorize',?,?,'SUCCEEDED','{}'::jsonb,?::jsonb)",
                key,
                CanonicalJson.sha256(Map.of("fixture", i)),
                a,
                payment,
                CanonicalJson.string(
                    Map.of(
                        "operation_key",
                        key,
                        "state",
                        "SUCCEEDED",
                        "business_state",
                        "CAPTURED",
                        "provider_account_id",
                        a.toString(),
                        "resource_id",
                        payment.toString())));
          }
          fixture.update("UPDATE sim_control SET source_sequence=? WHERE id=1", sequence);
        });
    var before = CanonicalJson.parse(send("GET", "/v1/inventory/summary/", null).body());
    long expected = ((Number) before.get("operation_count")).longValue();
    long opCursor = 0, seen = 0, snapshot = -1;
    do {
      String path =
          "/v1/inventory/?after=" + opCursor + (snapshot < 0 ? "" : "&snapshot=" + snapshot);
      var page = CanonicalJson.parse(send("GET", path, null).body());
      snapshot = ((Number) page.get("snapshot")).longValue();
      seen += ((List<?>) page.get("operations")).size();
      opCursor = ((Number) page.get("next_after")).longValue();
      if (Boolean.TRUE.equals(page.get("complete"))) break;
    } while (true);
    assertEquals(expected, seen);
    assertTrue(seen > 250);
    long sourceCursor = 0, sourceSeen = 0, sourceSnapshot = -1;
    do {
      String path =
          "/v1/inventory/sources/?after="
              + sourceCursor
              + (sourceSnapshot < 0 ? "" : "&snapshot=" + sourceSnapshot);
      var page = CanonicalJson.parse(send("GET", path, null).body());
      sourceSnapshot = ((Number) page.get("snapshot")).longValue();
      sourceSeen += ((List<?>) page.get("sources")).size();
      sourceCursor = ((Number) page.get("next_after")).longValue();
      if (Boolean.TRUE.equals(page.get("complete"))) break;
    } while (true);
    assertEquals(((Number) before.get("source_count")).longValue(), sourceSeen);
    assertTrue(sourceSeen > 250);
    post(
        "/v1/refund/",
        envelope(
            a,
            first,
            UUID.randomUUID().toString(),
            Map.of("amount_minor", 1, "reason", "Inventory change")));
    var after = CanonicalJson.parse(send("GET", "/v1/inventory/summary/", null).body());
    assertNotEquals(before.get("inventory_hash"), after.get("inventory_hash"));
    var release = envelope(a, a, UUID.randomUUID().toString(), Map.of());
    release.put("inventory_hash", before.get("inventory_hash"));
    release.put("acknowledged_cursor", after.get("event_high_water"));
    assertEquals(409, send("POST", "/v1/control/restore-release/", release).statusCode());
    release.put("inventory_hash", after.get("inventory_hash"));
    assertEquals(200, send("POST", "/v1/control/restore-release/", release).statusCode());
  }

  @Test
  void retirementAndSeparatePurgeAuthorityPreservePermanentNamespace() throws Exception {
    var ownerData =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            System.getenv("TEST_SIMULATOR_DB_URL"),
            "simulator_owner",
            System.getenv("TEST_SIMULATOR_OWNER_PASSWORD"));
    var ownerDb = new org.springframework.jdbc.core.JdbcTemplate(ownerData);
    ownerDb.setQueryTimeout(30);
    UUID a = UUID.randomUUID(), batch = UUID.randomUUID(), approval = UUID.randomUUID();
    Instant old = Instant.now().minus(Duration.ofDays(400));
    String operationKey = UUID.randomUUID().toString(),
        fingerprint = CanonicalJson.sha256(Map.of("fixture", true));
    // An explicitly historical owner fixture exercises purge guards; it is not evidence of 400 days
    // of elapsed product operation.
    var ownerTx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ownerData));
    ownerTx.executeWithoutResult(
        s -> {
          ownerDb.queryForObject("SELECT id FROM sim_control WHERE id=1 FOR UPDATE", Integer.class);
          ownerDb.update(
              "INSERT INTO sim_accounts(id,merchant_id,environment_id,created_at) VALUES(?,?,?,?)",
              a,
              a,
              ENV,
              java.sql.Timestamp.from(old));
          ownerDb.update(
              "INSERT INTO"
                  + " sim_batches(id,account_id,cutoff_identity,net_minor,state,created_at,closed_at)"
                  + " VALUES(?,?,?,0,'COMPLETED',?,?)",
              batch,
              a,
              "historical_fixture",
              java.sql.Timestamp.from(old),
              java.sql.Timestamp.from(old));
          Long sequence =
              ownerDb.queryForObject(
                  "UPDATE sim_control SET source_sequence=source_sequence+1 WHERE id=1 RETURNING"
                      + " source_sequence",
                  Long.class);
          ownerDb.update(
              "INSERT INTO"
                  + " sim_sources(source_id,sequence,account_id,resource_id,event_type,amount_minor,occurred_at,reference,batch_id,clearing_line)"
                  + " VALUES(?,?,?,?,'BATCH_ZERO',0,?,?,?,false)",
              batch,
              sequence,
              a,
              batch,
              java.sql.Timestamp.from(old),
              batch.toString(),
              batch);
          ownerDb.update(
              "INSERT INTO"
                  + " sim_operations(operation_key,payload_hash,kind,account_id,resource_id,state,payload,result,created_at)"
                  + " VALUES(?,?,'fixture',?,?,'SUCCEEDED','{}'::jsonb,?::jsonb,?)",
              operationKey,
              fingerprint,
              a,
              batch,
              CanonicalJson.string(Map.of("state", "SUCCEEDED", "business_state", "COMPLETED")),
              java.sql.Timestamp.from(old));
        });
    String purgeSecret = "separate-isolated-purge-test-authority";
    var ownerStore = new ProviderStore(ownerDb, ENV);
    var configureEnv =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("mprp.command", "configure-purge")
            .withProperty("mprp.purge-secret", purgeSecret);
    new ProviderPurgeCommand(
            ownerStore,
            configureEnv,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ownerData))
        .run(new org.springframework.boot.DefaultApplicationArguments());
    var summary =
        CanonicalJson.parse(
            send("GET", "/v1/inventory/summary/?provider_account_id=" + a, null).body());
    String hash = summary.get("inventory_hash").toString();
    var retirement = envelope(a, a, UUID.randomUUID().toString(), Map.of());
    retirement.put("approval_id", approval.toString());
    retirement.put("inventory_hash", hash);
    retirement.put(
        "merchant_closed_at", CanonicalJson.instant(Instant.now().minus(Duration.ofDays(364))));
    assertEquals(403, send("POST", "/v1/accounts/" + a + "/retire/", retirement).statusCode());
    assertEquals(
        409, operatorPost("/v1/accounts/" + a + "/retire/", retirement, purgeSecret).statusCode());
    retirement.put("merchant_closed_at", CanonicalJson.instant(old));
    assertEquals(
        200,
        retireWithOriginalRequest("/v1/accounts/" + a + "/retire/", retirement, purgeSecret)
            .statusCode());
    var purgeData =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            System.getenv("TEST_SIMULATOR_DB_URL"),
            "simulator_purge",
            System.getenv("TEST_SIMULATOR_PURGE_PASSWORD"));
    var purgeDb = new org.springframework.jdbc.core.JdbcTemplate(purgeData);
    purgeDb.setQueryTimeout(5);
    var purgeStore = new ProviderStore(purgeDb, ENV);
    var purgeEnv =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("mprp.command", "purge")
            .withProperty("mprp.purge-secret", "incorrect-but-long-enough-purge-secret")
            .withProperty("mprp.purge-account", a.toString())
            .withProperty("mprp.purge-approval", approval.toString())
            .withProperty("mprp.purge-inventory-hash", hash);
    var purgeCommand =
        new ProviderPurgeCommand(
            purgeStore,
            purgeEnv,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(purgeData));
    assertThrows(
        IllegalStateException.class,
        () -> purgeCommand.run(new org.springframework.boot.DefaultApplicationArguments()));
    assertEquals(
        1,
        ownerDb.queryForObject(
            "SELECT count(*) FROM sim_sources WHERE account_id=?", Integer.class, a));
    purgeEnv.setProperty("mprp.purge-secret", purgeSecret);
    purgeCommand.run(new org.springframework.boot.DefaultApplicationArguments());
    purgeCommand.run(new org.springframework.boot.DefaultApplicationArguments());
    assertEquals(
        0,
        ownerDb.queryForObject(
            "SELECT count(*) FROM sim_sources WHERE account_id=?", Integer.class, a));
    assertEquals(
        1,
        ownerDb.queryForObject(
            "SELECT count(*) FROM sim_operations WHERE operation_key=?",
            Integer.class,
            operationKey));
    assertEquals(
        "PURGED",
        ownerDb.queryForObject(
            "SELECT result->>'business_state' FROM sim_operations WHERE operation_key=?",
            String.class,
            operationKey));
    assertEquals(
        409,
        send(
                "POST",
                "/v1/authorize/",
                envelope(
                    a,
                    UUID.randomUUID(),
                    UUID.randomUUID().toString(),
                    Map.of(
                        "amount_minor",
                        100,
                        "token",
                        "tok_demo_success",
                        "capture_mode",
                        "AUTOMATIC")))
            .statusCode());
    var completed =
        CanonicalJson.parse(send("GET", "/v1/accounts/" + a + "/retirement/", null).body());
    assertEquals(true, completed.get("completed"));
  }

  @Test
  void expiryAndDailySettlementEmitRetainedSystemEvents() throws Exception {
    UUID a = UUID.randomUUID(), manual = UUID.randomUUID(), automatic = UUID.randomUUID();
    String authKey = UUID.randomUUID().toString();
    post(
        "/v1/authorize/",
        envelope(
            a,
            manual,
            authKey,
            Map.of("amount_minor", 10000, "token", "tok_demo_success", "capture_mode", "MANUAL")));
    post(
        "/v1/authorize/",
        envelope(
            a,
            automatic,
            UUID.randomUUID().toString(),
            Map.of(
                "amount_minor", 10000, "token", "tok_demo_success", "capture_mode", "AUTOMATIC")));
    var data =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            System.getenv("TEST_SIMULATOR_DB_URL"),
            "simulator_owner",
            System.getenv("TEST_SIMULATOR_OWNER_PASSWORD"));
    var fixture = new org.springframework.jdbc.core.JdbcTemplate(data);
    fixture.setQueryTimeout(30);
    fixture.update(
        "UPDATE sim_payments SET authorization_expires_at=now()-interval '1 second' WHERE id=?",
        manual);
    fixture.update(
        "UPDATE sim_batches SET created_at=date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE"
            + " 'UTC'-interval '1 day' WHERE account_id=? AND state='OPEN'",
        a);
    long before =
        ((Number)
                CanonicalJson.parse(send("GET", "/v1/capabilities/", null).body())
                    .get("event_high_water"))
            .longValue();
    provider.progress();
    provider.dailySettlements();
    provider.dailySettlements();
    var expired = CanonicalJson.parse(send("GET", "/v1/payments/" + manual + "/", null).body());
    assertEquals("EXPIRED", expired.get("business_state"));
    var account = CanonicalJson.parse(send("GET", "/v1/accounts/" + a + "/", null).body());
    assertEquals(9680, ((Number) account.get("bank_balance_minor")).intValue());
    @SuppressWarnings("unchecked")
    var events =
        (List<Map<String, Object>>)
            CanonicalJson.parse(send("GET", "/v1/events/?after=" + before, null).body())
                .get("events");
    assertEquals(
        1,
        events.stream().filter(e -> "authorization_expired".equals(e.get("event_type"))).count());
    assertEquals(
        1, events.stream().filter(e -> "settlement_completed".equals(e.get("event_type"))).count());
    assertEquals(
        "FAILED",
        post(
                "/v1/capture/",
                envelope(a, manual, UUID.randomUUID().toString(), Map.of("amount_minor", 10000)))
            .get("state"));
  }

  @Test
  void eventFeedHasByteBoundAndPersistsOversizeDisposition() throws Exception {
    UUID account = UUID.randomUUID();
    post("/v1/accounts/", envelope(account, account, UUID.randomUUID().toString(), Map.of()));
    long before =
        ((Number)
                CanonicalJson.parse(send("GET", "/v1/capabilities/", null).body())
                    .get("event_high_water"))
            .longValue();
    var data =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            System.getenv("TEST_SIMULATOR_DB_URL"),
            "simulator_owner",
            System.getenv("TEST_SIMULATOR_OWNER_PASSWORD"));
    var fixture = new org.springframework.jdbc.core.JdbcTemplate(data);
    fixture.setQueryTimeout(30);
    var fixtureTx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(data));
    UUID oversized = UUID.randomUUID();
    // Explicit nonmonetary owner fixture isolates response budgeting from payment workload
    // capacity.
    fixtureTx.executeWithoutResult(
        t -> {
          long sequence =
              fixture.queryForObject(
                  "SELECT event_sequence FROM sim_control WHERE id=1 FOR UPDATE", Long.class);
          for (int i = 0; i < 40; i++) {
            UUID id = UUID.randomUUID();
            Map<String, Object> event =
                Map.of(
                    "event_id",
                    id.toString(),
                    "sequence",
                    ++sequence,
                    "event_type",
                    "nonmonetary_fixture",
                    "provider_account_id",
                    account.toString(),
                    "padding",
                    "x".repeat(32000));
            fixture.update(
                "INSERT INTO sim_events(sequence,event_id,account_id,body) VALUES(?,?,?,?::jsonb)",
                sequence,
                id,
                account,
                CanonicalJson.string(event));
          }
          Map<String, Object> event =
              Map.of(
                  "event_id",
                  oversized.toString(),
                  "sequence",
                  ++sequence,
                  "event_type",
                  "nonmonetary_oversize_fixture",
                  "provider_account_id",
                  account.toString(),
                  "padding",
                  "x".repeat(1024 * 1024));
          fixture.update(
              "INSERT INTO sim_events(sequence,event_id,account_id,body) VALUES(?,?,?,?::jsonb)",
              sequence,
              oversized,
              account,
              CanonicalJson.string(event));
          fixture.update("UPDATE sim_control SET event_sequence=? WHERE id=1", sequence);
        });
    long cursor = before;
    int seen = 0;
    boolean blocked = false;
    for (int page = 0; page < 10; page++) {
      var response = send("GET", "/v1/events/?after=" + cursor, null);
      assertEquals(200, response.statusCode());
      assertTrue(
          response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 900 * 1024);
      var body = CanonicalJson.parse(response.body());
      @SuppressWarnings("unchecked")
      var events = (List<Map<String, Object>>) body.get("events");
      for (var event : events) assertEquals(++cursor, ((Number) event.get("sequence")).longValue());
      seen += events.size();
      assertEquals(cursor, ((Number) body.get("next_after")).longValue());
      if (Boolean.TRUE.equals(body.get("blocked"))) {
        blocked = true;
        assertEquals(oversized.toString(), body.get("blocked_event_id"));
        break;
      }
    }
    assertEquals(40, seen);
    assertTrue(blocked);
    assertEquals(
        "EVENT_EXCEEDS_PROTOCOL_LIMIT",
        fixture.queryForObject(
            "SELECT code FROM sim_event_failure WHERE event_id=?", String.class, oversized));
    assertEquals(
        true,
        CanonicalJson.parse(send("GET", "/v1/capabilities/", null).body())
            .get("event_delivery_blocked"));
    var ack = envelope(account, account, UUID.randomUUID().toString(), Map.of());
    ack.put("cursor", cursor + 1);
    post("/v1/events/ack/", ack);
    assertEquals(
        "ACKNOWLEDGED",
        fixture.queryForObject(
            "SELECT state FROM sim_event_failure WHERE event_id=?", String.class, oversized));
  }

  @Test
  void runtimeCannotCreateTemporaryObjectsOrRewriteProviderEvents() throws Exception {
    var data =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            System.getenv("TEST_SIMULATOR_DB_URL"),
            "simulator_runtime",
            System.getenv("TEST_SIMULATOR_DB_PASSWORD"));
    var runtime = new org.springframework.jdbc.core.JdbcTemplate(data);
    runtime.setQueryTimeout(5);
    assertEquals(
        false,
        runtime.queryForObject(
            "SELECT has_database_privilege(current_user,current_database(),'TEMP')",
            Boolean.class));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> runtime.execute("CREATE TEMP TABLE journal(id int)"));
    UUID account = UUID.randomUUID();
    post("/v1/accounts/", envelope(account, account, UUID.randomUUID().toString(), Map.of()));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> runtime.update("UPDATE sim_events SET body='{}'::jsonb WHERE account_id=?", account));
  }

  @Test
  void thousandPaymentSettlementUsesImmutableSummaryAndCompleteBatchHistory() throws Exception {
    UUID account = UUID.randomUUID();
    UUID batch = UUID.randomUUID();
    UUID resource = UUID.randomUUID();
    post("/v1/accounts/", envelope(account, account, UUID.randomUUID().toString(), Map.of()));
    var data =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            System.getenv("TEST_SIMULATOR_DB_URL"),
            "simulator_owner",
            System.getenv("TEST_SIMULATOR_OWNER_PASSWORD"));
    var fixture = new org.springframework.jdbc.core.JdbcTemplate(data);
    fixture.setQueryTimeout(30);
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(data));
    transaction.setTimeout(60);
    // Bulk owner setup isolates the real provider settlement/history boundary from load generation.
    transaction.executeWithoutResult(
        ignored -> {
          long sequence =
              fixture.queryForObject(
                  "SELECT source_sequence FROM sim_control WHERE id=1 FOR UPDATE", Long.class);
          fixture.update(
              "INSERT INTO sim_batches(id,account_id,state) VALUES(?,?,'OPEN')", batch, account);
          fixture.update(
              "INSERT INTO"
                  + " sim_payments(id,account_id,authorized_minor,captured_minor,fee_minor,state,authorization_expires_at)"
                  + " SELECT gen_random_uuid(),?,10000,10000,320,'CAPTURED',now()+interval '7 days'"
                  + " FROM generate_series(1,1000)",
              account);
          fixture.update(
              "INSERT INTO"
                  + " sim_sources(source_id,sequence,account_id,resource_id,payment_id,event_type,amount_minor,occurred_at,reference,batch_id,clearing_line)"
                  + " SELECT"
                  + " source_id,?+source_ordinal,?,payment_id,payment_id,event_type,amount,now(),source_id::text,?,true"
                  + " FROM (SELECT gen_random_uuid() AS source_id,p.id AS"
                  + " payment_id,v.event_type,v.amount,row_number() OVER(ORDER BY p.id,v.ordinal)"
                  + " AS source_ordinal FROM sim_payments p CROSS JOIN"
                  + " (VALUES('CAPTURE'::text,10000::bigint,0),('FEE',-320,1))"
                  + " v(event_type,amount,ordinal) WHERE p.account_id=?) facts",
              sequence,
              account,
              batch,
              account);
          fixture.update("UPDATE sim_control SET source_sequence=? WHERE id=1", sequence + 2000);
        });
    long before =
        ((Number)
                CanonicalJson.parse(send("GET", "/v1/capabilities/", null).body())
                    .get("event_high_water"))
            .longValue();
    String key = UUID.randomUUID().toString();
    var request = envelope(account, resource, key, Map.of("cutoff_identity", key));
    var response = send("POST", "/v1/flush/", request);
    assertEquals(200, response.statusCode(), response.body());
    assertTrue(
        response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 128 * 1024);
    var summary = CanonicalJson.parse(response.body());
    assertEquals(true, summary.get("history_required"));
    assertEquals(batch.toString(), summary.get("history_resource_id"));
    assertEquals(resource.toString(), summary.get("resource_id"));
    assertEquals(2001, ((Number) summary.get("history_source_count")).intValue());
    assertEquals(9680000, ((Number) summary.get("net_minor")).longValue());
    assertEquals(List.of(), summary.get("sources"));
    assertFalse(summary.containsKey("details"));
    @SuppressWarnings("unchecked")
    var batches = (List<Map<String, Object>>) summary.get("batches");
    assertEquals(1, batches.size());
    assertFalse(batches.getFirst().containsKey("sources"));
    assertFalse(batches.getFirst().containsKey("details"));
    long high = ((Number) summary.get("source_high_water")).longValue();
    long cursor = 0;
    long economicNet = 0;
    int seen = 0;
    int transfers = 0;
    for (int page = 0; page < 20; page++) {
      var history =
          CanonicalJson.parse(
              send("GET", "/v1/history/" + batch + "/?after=" + cursor + "&snapshot=" + high, null)
                  .body());
      assertEquals(high, ((Number) history.get("source_high_water")).longValue());
      @SuppressWarnings("unchecked")
      var sources = (List<Map<String, Object>>) history.get("sources");
      assertTrue(sources.size() <= 250);
      for (var source : sources) {
        long sequence = ((Number) source.get("sequence")).longValue();
        assertTrue(sequence > cursor);
        cursor = sequence;
        seen++;
        if (Boolean.TRUE.equals(source.get("clearing_line")))
          economicNet =
              Math.addExact(economicNet, ((Number) source.get("amount_minor")).longValue());
        if ("SETTLEMENT".equals(source.get("event_type"))) transfers++;
      }
      assertEquals(cursor, ((Number) history.get("next_after")).longValue());
      if (Boolean.TRUE.equals(history.get("complete"))) break;
    }
    assertEquals(2001, seen);
    assertEquals(1, transfers);
    assertEquals(9680000, economicNet);
    assertEquals(
        CanonicalJson.string(summary), CanonicalJson.string(post("/v1/resolve-key/", request)));
    assertEquals(CanonicalJson.string(summary), CanonicalJson.string(post("/v1/flush/", request)));
    @SuppressWarnings("unchecked")
    var events =
        (List<Map<String, Object>>)
            CanonicalJson.parse(send("GET", "/v1/events/?after=" + before, null).body())
                .get("events");
    assertEquals(1, events.size());
    @SuppressWarnings("unchecked")
    var eventResult = (Map<String, Object>) events.getFirst().get("result");
    assertEquals(CanonicalJson.string(summary), CanonicalJson.string(eventResult));
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT count(*) FROM sim_transfers WHERE batch_id=?", Integer.class, batch));
  }

  @Test
  void immutableManifestReusesExactBytesAndFreezesHighWater() throws Exception {
    UUID a = UUID.randomUUID(), p = UUID.randomUUID();
    post(
        "/v1/authorize/",
        envelope(
            a,
            p,
            UUID.randomUUID().toString(),
            Map.of(
                "amount_minor", 10000, "token", "tok_demo_success", "capture_mode", "AUTOMATIC")));
    Instant start = Instant.now().minusSeconds(60), end = start.plusSeconds(120);
    var query =
        Map.of(
            "provider_account_id",
            a.toString(),
            "kind",
            "PROCESSOR_ECONOMIC",
            "period_start",
            CanonicalJson.instant(start),
            "period_end",
            CanonicalJson.instant(end));
    var m = post("/v1/statements/", query);
    assertEquals(2, ((Number) m.get("row_count")).intValue());
    assertEquals(9680, ((Number) m.get("net_minor")).intValue());
    var same = post("/v1/statements/", query);
    assertEquals(m.get("manifest_id"), same.get("manifest_id"));
    String path = "/v1/statements/" + m.get("manifest_id") + "/csv/";
    var csv = send("GET", path, null);
    assertEquals(200, csv.statusCode());
    assertEquals(
        m.get("csv_sha256"),
        CanonicalJson.digest(csv.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    post(
        "/v1/refund/",
        envelope(
            a,
            p,
            UUID.randomUUID().toString(),
            Map.of("amount_minor", 2500, "reason", "Fictional refund")));
    var newer = post("/v1/statements/", query);
    assertNotEquals(m.get("manifest_id"), newer.get("manifest_id"));
    assertEquals(3, ((Number) newer.get("row_count")).intValue());
    assertEquals(7180, ((Number) newer.get("net_minor")).intValue());
    assertEquals(
        m.get("content_hash"),
        CanonicalJson.parse(
                send("GET", "/v1/statements/" + m.get("manifest_id") + "/", null).body())
            .get("content_hash"));
  }
}
