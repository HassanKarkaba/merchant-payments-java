package com.hassankarkaba.mprp.merchants;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hassankarkaba.mprp.accounts.AccountService;
import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.Db;
import com.hassankarkaba.mprp.operations.DomainException;
import com.hassankarkaba.mprp.protocol.CanonicalJson;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual runtime-role closure SQL and transaction boundaries; identity/step-up are controlled. */
@EnabledIfEnvironmentVariable(named = "MPRP_MAIN_TEST_JDBC", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantClosureIntegrationTest {
  private AnnotationConfigApplicationContext context;
  private JdbcTemplate owner;
  private Db db;
  private Authz auth;
  private AccountService accounts;
  private MerchantService merchants;

  @BeforeAll
  void setup() {
    String url = System.getenv("MPRP_MAIN_TEST_JDBC");
    assertTrue(url.contains("mprp_java_verify"), "Refusing a nonverification database");
    String ownerPassword = System.getenv("MPRP_MAIN_TEST_OWNER_PASSWORD"),
        runtimePassword = System.getenv("MPRP_MAIN_TEST_RUNTIME_PASSWORD");
    org.flywaydb.core.Flyway.configure()
        .dataSource(url, "mprp_owner", ownerPassword)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    owner = new JdbcTemplate(new DriverManagerDataSource(url, "mprp_owner", ownerPassword));
    owner.update(
        "insert into system_control(id,environment_id,provider_instance_id,restore_hold)"
            + " values(true,?,?,false) on conflict do nothing",
        UUID.randomUUID(),
        UUID.randomUUID());
    var dataSource = new DriverManagerDataSource(url, "mprp_runtime", runtimePassword);
    auth = mock(Authz.class);
    accounts = mock(AccountService.class);
    context = new AnnotationConfigApplicationContext();
    context.registerBean("dataSource", javax.sql.DataSource.class, () -> dataSource);
    context.registerBean(
        "transactionManager",
        org.springframework.transaction.PlatformTransactionManager.class,
        () -> new DataSourceTransactionManager(dataSource));
    context.registerBean(Db.class, () -> new Db(new JdbcTemplate(dataSource)));
    context.registerBean(Authz.class, () -> auth);
    context.registerBean(AccountService.class, () -> accounts);
    context.registerBean(
        MerchantService.class,
        () ->
            new MerchantService(
                context.getBean(Db.class), auth, accounts, "http://fixture.invalid"));
    context.register(TxConfiguration.class);
    context.refresh();
    db = context.getBean(Db.class);
    merchants = context.getBean(MerchantService.class);
  }

  @AfterAll
  void close() {
    if (context != null) context.close();
  }

  @org.springframework.context.annotation.Configuration
  @EnableTransactionManagement
  static class TxConfiguration {}

  private record Fixture(UUID merchant, UUID actor) {}

  private Fixture fixture() {
    UUID merchant = UUID.randomUUID(), actor = UUID.randomUUID();
    owner.update(
        "insert into app_user(id,email,verified,totp_confirmed) values(?,?,true,true)",
        actor,
        actor + "@demo.invalid");
    owner.update(
        "insert into merchant(id,name,status,provider_account_id,bank_account_id) values(?,'Closure"
            + " verification','ACTIVE',?,?)",
        merchant,
        merchant,
        merchant);
    when(auth.require(merchant, "OWNER"))
        .thenAnswer(
            invocation -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              db.lockMerchant(merchant);
              return actor;
            });
    return new Fixture(merchant, actor);
  }

  private String status(Fixture fixture) {
    return db.scalar("select status from merchant where id=?", String.class, fixture.merchant);
  }

  private void blocked(Fixture fixture) {
    assertEquals(
        409,
        assertThrows(DomainException.class, () -> merchants.requestClose(fixture.merchant))
            .status());
    assertEquals("ACTIVE", status(fixture));
  }

  @Test
  void unsealedZeroSourceBlocksThenExactSealedBatchAllowsClosingWithOpenPeriod() {
    Fixture f = fixture();
    UUID source = UUID.randomUUID();
    Instant occurred = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS);
    LocalDate month = occurred.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    owner.update(
        "insert into accounting_period(merchant_id,month,status) values(?,?,'OPEN')",
        f.merchant,
        month);
    owner.update(
        "insert into"
            + " economic_source(id,merchant_id,provider_source_id,event_type,amount_minor,occurred_at,batch_id,resource_id,recognized,posting_month)"
            + " values(?,?,?,'BATCH_ZERO',0,?,?,?,true,?)",
        UUID.randomUUID(),
        f.merchant,
        source.toString(),
        Timestamp.from(occurred),
        source.toString(),
        source,
        month);
    blocked(f);
    verify(accounts, never()).event(f.actor, f.merchant, "MERCHANT_CLOSURE_REQUESTED");
    var evidence =
        Map.of(
            "source_id",
            source.toString(),
            "event_type",
            "BATCH_ZERO",
            "amount_minor",
            0,
            "batch_id",
            source.toString(),
            "resource_id",
            source.toString(),
            "occurred_at",
            CanonicalJson.instant(occurred),
            "provider_account_id",
            f.merchant.toString(),
            "currency",
            "USD");
    owner.update(
        "insert into"
            + " settlement_batch(id,merchant_id,provider_id,net_minor,sources_json,occurred_at)"
            + " values(?,?,?,0,?,?)",
        UUID.randomUUID(),
        f.merchant,
        source.toString(),
        CanonicalJson.string(java.util.List.of(evidence)),
        Timestamp.from(occurred));
    merchants.requestClose(f.merchant);
    assertEquals("CLOSING", status(f));
    assertEquals(
        "OPEN",
        db.scalar(
            "select status from accounting_period where merchant_id=? and month=?",
            String.class,
            f.merchant,
            month));
    verify(accounts).event(f.actor, f.merchant, "MERCHANT_CLOSURE_REQUESTED");
  }

  @Test
  void acceptedCallbackBlocksUntilItsDurableApplication() {
    Fixture f = fixture();
    UUID inbox = UUID.randomUUID();
    var payload =
        Map.of("provider_account_id", f.merchant.toString(), "event_id", inbox.toString());
    owner.update(
        "insert into callback_inbox(id,digest,payload) values(?,?,?)",
        inbox,
        CanonicalJson.sha256(payload),
        CanonicalJson.string(payload));
    blocked(f);
    owner.update("update callback_inbox set state='APPLIED' where id=?", inbox);
    merchants.requestClose(f.merchant);
    assertEquals("CLOSING", status(f));
  }

  @Test
  void manualProviderHistoryObligationBlocksUntilItIsDone() {
    Fixture f = fixture();
    UUID job = UUID.randomUUID();
    owner.update(
        "insert into durable_job(id,kind,resource_id,merchant_id,state)"
            + " values(?,'PROVIDER_HISTORY',?,?,'MANUAL')",
        job,
        UUID.randomUUID(),
        f.merchant);
    owner.update("update system_control set unfinished_jobs=unfinished_jobs+1 where id=true");
    blocked(f);
    owner.update("update durable_job set state='DONE',generation=generation+1 where id=?", job);
    owner.update("update system_control set unfinished_jobs=unfinished_jobs-1 where id=true");
    merchants.requestClose(f.merchant);
    assertEquals("CLOSING", status(f));
  }
}
