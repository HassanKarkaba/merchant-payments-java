package com.hassankarkaba.mprp.accounts;

import com.hassankarkaba.mprp.operations.Db;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Key IDs are immutable names. A changed key must receive a fresh ID. */
@Component
@Order(10)
public class EncryptionKeyRegistry implements ApplicationRunner {
  private final Db db;
  private final SecretCodec secrets;
  private final TransactionTemplate tx;

  public EncryptionKeyRegistry(Db db, SecretCodec secrets, PlatformTransactionManager manager) {
    this.db = db;
    this.secrets = secrets;
    this.tx = new TransactionTemplate(manager);
  }

  public void run(ApplicationArguments args) {
    tx.executeWithoutResult(
        s -> {
          for (var key : secrets.keyFingerprints().entrySet()) {
            db.exec(
                "insert into encryption_key_identity(key_id,material_digest) values (?,?) on"
                    + " conflict do nothing",
                key.getKey(),
                key.getValue());
            if (!key.getValue()
                .equals(
                    db.scalar(
                        "select material_digest from encryption_key_identity where key_id=?",
                        String.class,
                        key.getKey())))
              throw new IllegalStateException(
                  "Encryption key ID was rebound to different material. Preserve its old value and"
                      + " use a fresh key ID.");
          }
          // Qualify legacy upgrade data before admitting a newly registered key configuration.
          for (var row :
              db.query(
                  "select id,totp_cipher from app_user where totp_cipher is not null order by id"
                      + " limit 1"))
            secrets.decrypt(row.get("totp_cipher").toString(), "totp:" + row.get("id"));
        });
  }
}
