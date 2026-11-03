package com.hassankarkaba.mprp.operations;

import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Order(-5)
public class StartupChecks implements ApplicationRunner {
  private final Db db;
  private final DataSource data;
  private final Environment env;

  public StartupChecks(Db db, DataSource data, Environment env) {
    this.db = db;
    this.data = data;
    this.env = env;
  }

  public void run(ApplicationArguments args) {
    requireChoice("MPRP_CURRENCY", "USD");
    requireChoice("MPRP_MODE", "SANDBOX");
    requireChoice("MPRP_SCHEMA_VERSION", "1");
    requireChoice("MPRP_POLICY_VERSION", "mprp_v1");
    String mail = env.getProperty("spring.mail.host", "mailpit");
    if (!Set.of("mailpit", "localhost", "127.0.0.1").contains(mail)
        || !env.getProperty("spring.mail.username", "").isBlank()
        || !env.getProperty("spring.mail.password", "").isBlank())
      throw new IllegalStateException("Only local unauthenticated Mailpit SMTP is supported");
    if (Set.of("serve", "seed-demo", "rotate-encryption")
        .contains(env.getProperty("mprp.command", "serve"))) {
      boolean safe =
          db.scalar(
                      "select count(*) from pg_roles where rolname=current_user and not rolsuper"
                          + " and not rolbypassrls and not rolcreatedb and not rolcreaterole",
                      Long.class)
                  == 1
              && !db.scalar(
                  "select has_schema_privilege(current_user,'public','CREATE') or"
                      + " has_database_privilege(current_user,current_database(),'TEMP')",
                  Boolean.class);
      if (!safe) throw new IllegalStateException("Unsafe runtime database role");
      var flyway =
          Flyway.configure().dataSource(data).ignoreMigrationPatterns(new String[0]).load();
      flyway.validate();
      if (flyway.info().pending().length != 0)
        throw new IllegalStateException(
            "Explicit schema migration is required before runtime startup");
      long registered = db.scalar("select count(*) from guard_registry", Long.class),
          intact =
              db.scalar(
                  "select count(*) from guard_registry g join pg_trigger t on t.tgname=g.name where"
                      + " t.tgenabled='O' and"
                      + " g.definition_hash=md5(pg_get_triggerdef(t.oid)||pg_get_functiondef(t.tgfoid))",
                  Long.class);
      if (registered != 42 || intact != 42)
        throw new IllegalStateException("Required database guards are missing or changed");
    }
  }

  private void requireChoice(String name, String expected) {
    String value = env.getProperty(name, expected);
    if (!expected.equals(value))
      throw new IllegalStateException("Unsupported local configuration: " + name);
  }
}
