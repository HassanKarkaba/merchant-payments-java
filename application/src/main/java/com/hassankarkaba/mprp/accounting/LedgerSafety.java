package com.hassankarkaba.mprp.accounting;

import static com.hassankarkaba.mprp.operations.DomainException.require;

import com.hassankarkaba.mprp.operations.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class LedgerSafety {
  private final Db db;

  public LedgerSafety(Db db) {
    this.db = db;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requirePosting() {
    db.exec(
        "lock table journal,ledger_entry,economic_source,accounting_period in row exclusive mode");
    boolean role =
        db.scalar(
                    "select count(*) from pg_roles where rolname=current_user and not rolsuper and"
                        + " not rolbypassrls and not rolcreatedb and not rolcreaterole",
                    Long.class)
                == 1
            && !db.scalar(
                "select has_schema_privilege(current_user,'public','CREATE') or"
                    + " has_database_privilege(current_user,current_database(),'TEMP')",
                Boolean.class);
    long guards =
        db.scalar(
            "select count(*) from guard_registry g join pg_trigger t on t.tgname=g.name where"
                + " t.tgenabled='O' and"
                + " g.definition_hash=md5(pg_get_triggerdef(t.oid)||pg_get_functiondef(t.tgfoid))",
            Long.class);
    require(
        role
            && guards == 42
            && db.scalar(
                "select current_setting('fsync')='on' and"
                    + " current_setting('synchronous_commit')='on' and"
                    + " current_setting('full_page_writes')='on'",
                Boolean.class),
        503,
        "Accounting is held until its safe role, exact guards and durability are restored");
  }
}
