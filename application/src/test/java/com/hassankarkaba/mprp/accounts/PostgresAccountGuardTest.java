package com.hassankarkaba.mprp.accounts;

import static org.junit.jupiter.api.Assertions.*;

import com.hassankarkaba.mprp.operations.Db;
import com.hassankarkaba.mprp.operations.DomainException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Real runtime-role commit boundaries; every row belongs to a fresh verification actor/merchant.
 */
@EnabledIfEnvironmentVariable(named = "MPRP_MAIN_TEST_JDBC", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresAccountGuardTest {
  private String url, ownerPassword, runtimePassword;

  @BeforeAll
  void environment() {
    url = System.getenv("MPRP_MAIN_TEST_JDBC");
    ownerPassword = System.getenv("MPRP_MAIN_TEST_OWNER_PASSWORD");
    runtimePassword = System.getenv("MPRP_MAIN_TEST_RUNTIME_PASSWORD");
    assertTrue(url.contains("mprp_java_verify"), "Refusing nonverification database");
    org.flywaydb.core.Flyway.configure()
        .dataSource(url, "mprp_owner", ownerPassword)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private Connection owner() throws SQLException {
    return DriverManager.getConnection(url, "mprp_owner", ownerPassword);
  }

  private Connection runtime() throws SQLException {
    return DriverManager.getConnection(url, "mprp_runtime", runtimePassword);
  }

  private void exec(Connection c, String sql, Object... args) throws SQLException {
    try (var statement = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
      statement.executeUpdate();
    }
  }

  private long count(Connection c, String sql, Object... args) throws SQLException {
    try (var statement = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private UUID user(Connection c, String hash) throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "insert into app_user(id,email,password_hash,verified,totp_confirmed) values"
            + " (?,?,?,true,true)",
        id,
        id + "@example.test",
        hash);
    return id;
  }

  private UUID merchant(Connection c, UUID owner) throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "insert into merchant(id,name,status,provider_account_id,bank_account_id) values"
            + " (?,'Isolated account acceptance','ACTIVE',?,?)",
        id,
        id,
        id);
    member(c, id, owner, "OWNER");
    return id;
  }

  private void member(Connection c, UUID merchant, UUID actor, String role) throws SQLException {
    exec(
        c,
        "insert into membership(id,user_id,merchant_id,role) values (?,?,?,?)",
        UUID.randomUUID(),
        actor,
        merchant,
        role);
  }

  @Test
  void runtimeRoleCannotDemoteRevokeOrDeactivateLastOwner() throws Exception {
    UUID actor, merchant;
    try (var c = owner()) {
      actor = user(c, null);
      merchant = merchant(c, actor);
    }
    try (var c = runtime()) {
      c.setAutoCommit(false);
      assertThrows(
          SQLException.class,
          () ->
              exec(
                  c,
                  "update membership set role='ANALYST' where merchant_id=? and user_id=?",
                  merchant,
                  actor));
      c.rollback();
      assertThrows(
          SQLException.class,
          () ->
              exec(
                  c,
                  "update membership set active=false where merchant_id=? and user_id=?",
                  merchant,
                  actor));
      c.rollback();
      assertThrows(
          SQLException.class, () -> exec(c, "update app_user set active=false where id=?", actor));
      c.rollback();
      assertEquals(
          1,
          count(
              c,
              "select count(*) from membership where merchant_id=? and active and role='OWNER'",
              merchant));
    }
  }

  @Test
  void concurrentMemberAdmissionsCannotExceedOneHundred() throws Exception {
    UUID merchant;
    try (var c = owner()) {
      UUID actor = user(c, null);
      merchant = merchant(c, actor);
      for (int i = 0; i < 98; i++) member(c, merchant, user(c, null), "ANALYST");
    }
    UUID[] actors = new UUID[2];
    try (var c = owner()) {
      actors[0] = user(c, null);
      actors[1] = user(c, null);
    }
    var barrier = new CyclicBarrier(2);
    var pool = Executors.newFixedThreadPool(2);
    List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
    try {
      for (UUID actor : actors)
        futures.add(
            pool.submit(
                () -> {
                  try (var c = runtime()) {
                    c.setAutoCommit(false);
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                      member(c, merchant, actor, "ANALYST");
                      c.commit();
                      return true;
                    } catch (SQLException refused) {
                      c.rollback();
                      return false;
                    }
                  }
                }));
      int accepted = 0;
      for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) accepted++;
      assertEquals(1, accepted);
      try (var c = runtime()) {
        assertEquals(
            100,
            count(c, "select count(*) from membership where merchant_id=? and active", merchant));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void crossConnectionLoginFailuresAreAtomicallyBoundedAndPersisted() throws Exception {
    var encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    String password = "A private verification phrase!";
    UUID actor;
    try (var c = owner()) {
      actor = user(c, encoder.encode(password));
    }
    var dataSource = new DriverManagerDataSource(url, "mprp_runtime", runtimePassword);
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean("dataSource", javax.sql.DataSource.class, () -> dataSource);
      context.registerBean(
          "transactionManager",
          org.springframework.transaction.PlatformTransactionManager.class,
          () -> new DataSourceTransactionManager(dataSource));
      context.registerBean(Db.class, () -> new Db(new JdbcTemplate(dataSource)));
      context.registerBean(RateLimiter.class);
      context.register(TxConfiguration.class);
      context.refresh();
      RateLimiter limiter = context.getBean(RateLimiter.class);
      String email = actor + "@example.test",
          source = SecretCodec.digest("verification-source:" + UUID.randomUUID());
      var barrier = new CyclicBarrier(8);
      var pool = Executors.newFixedThreadPool(8);
      List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
      try {
        for (int i = 0; i < 8; i++)
          futures.add(
              pool.submit(
                  () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                      return limiter
                              .authenticate(
                                  email, "incorrect verification password", source, encoder)
                              .isEmpty()
                          ? 400
                          : 200;
                    } catch (DomainException refusal) {
                      return refusal.status();
                    }
                  }));
        int failed = 0, limited = 0;
        for (var future : futures) {
          int status = future.get(20, TimeUnit.SECONDS);
          if (status == 400) failed++;
          else if (status == 429) limited++;
          else fail("Unexpected authentication result");
        }
        assertEquals(5, failed);
        assertEquals(3, limited);
        assertEquals(
            429,
            assertThrows(
                    DomainException.class,
                    () -> limiter.authenticate(email, password, source, encoder))
                .status());
        try (var c = runtime()) {
          assertEquals(
              5,
              count(
                  c,
                  "select count(*) from rate_event where bucket=?",
                  "auth-account:" + SecretCodec.digest(email)));
          assertEquals(
              5,
              count(c, "select count(*) from rate_event where bucket=?", "auth-source:" + source));
        }
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @org.springframework.context.annotation.Configuration
  @EnableTransactionManagement
  static class TxConfiguration {}
}
