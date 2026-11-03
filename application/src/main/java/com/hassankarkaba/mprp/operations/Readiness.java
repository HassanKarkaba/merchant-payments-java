package com.hassankarkaba.mprp.operations;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Readiness {
  private final Db db;
  private final com.hassankarkaba.mprp.protocol.ServiceKeys keys;

  public Readiness(Db db, com.hassankarkaba.mprp.protocol.ServiceKeys keys) {
    this.db = db;
    this.keys = keys;
  }

  public Map<String, Object> capabilities() {
    var result = new LinkedHashMap<String, Object>();
    boolean read = false,
        guards = false,
        safe = false,
        fresh = false,
        provider = false,
        hold = true,
        capacity = false,
        clock = false,
        broker = false;
    try {
      var c = db.one("select * from system_control where id=true");
      read = true;
      guards =
          db.scalar(
                  "select count(*) from guard_registry g join pg_trigger t on t.tgname=g.name where"
                      + " t.tgenabled='O' and"
                      + " g.definition_hash=md5(pg_get_triggerdef(t.oid)||pg_get_functiondef(t.tgfoid))",
                  Long.class)
              == 42;
      safe =
          db.scalar(
                      "select count(*) from pg_roles where rolname=current_user and not rolsuper"
                          + " and not rolbypassrls and not rolcreatedb and not rolcreaterole",
                      Long.class)
                  == 1
              && !db.scalar(
                  "select has_schema_privilege(current_user,'public','CREATE') or"
                      + " has_database_privilege(current_user,current_database(),'TEMP')",
                  Boolean.class)
              && db.scalar(
                  "select current_setting('fsync')='on' and"
                      + " current_setting('synchronous_commit')='on' and"
                      + " current_setting('full_page_writes')='on'",
                  Boolean.class);
      hold = (Boolean) c.get("restore_hold") || Boolean.TRUE.equals(c.get("provider_feed_blocked"));
      fresh = isFresh(c.get("worker_at"));
      provider = isFresh(c.get("provider_checked_at"));
      capacity =
          Db.number(c, "unfinished_jobs") < 10000 && Db.number(c, "unpublished_events") < 100000;
      broker = Boolean.TRUE.equals(c.get("broker_ready")) && isFresh(c.get("broker_checked_at"));
      clock = !((Timestamp) c.get("clock_at")).toInstant().isAfter(Instant.now().plusSeconds(2));
    } catch (Exception ignore) {
    }
    result.put("read", read);
    result.put(
        "financial_admission",
        read
            && guards
            && safe
            && fresh
            && provider
            && !hold
            && capacity
            && clock
            && broker
            && keys.current().active(Instant.now()));
    result.put(
        "callback", read && guards && safe && provider && keys.current().active(Instant.now()));
    result.put("worker", read && fresh && !hold);
    result.put("reconciliation", read && guards && safe && provider && !hold);
    result.put("notification", read && fresh && broker);
    result.put("recovery", read && safe);
    return result;
  }

  private boolean isFresh(Object t) {
    return t instanceof Timestamp x && x.toInstant().isAfter(Instant.now().minusSeconds(30));
  }
}
