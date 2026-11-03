package com.hassankarkaba.mprp.accounts;

import com.hassankarkaba.mprp.operations.AuditService;
import com.hassankarkaba.mprp.operations.Db;
import com.hassankarkaba.mprp.operations.DomainException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Offline, resumable encryption maintenance. No financial identity, policy or plaintext changes.
 */
@Service
public class EncryptionRotation {
  private final Db db;
  private final SecretCodec secrets;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final boolean worker;

  public EncryptionRotation(
      Db db,
      SecretCodec secrets,
      AuditService audit,
      PlatformTransactionManager manager,
      @Value("${mprp.worker:false}") boolean worker) {
    this.db = db;
    this.secrets = secrets;
    this.audit = audit;
    this.tx = new TransactionTemplate(manager);
    this.worker = worker;
  }

  public void rotate() {
    if (worker)
      throw new IllegalStateException("Stop web and all workers before rotating encryption keys.");
    tx.executeWithoutResult(
        s -> {
          if (db.scalar(
                      "select count(*) from system_control where"
                          + " worker_at>clock_timestamp()-interval '30 seconds'",
                      Long.class)
                  > 0
              || db.scalar(
                      "select count(*) from durable_job where state='RUNNING' and"
                          + " lease_until>clock_timestamp()",
                      Long.class)
                  > 0)
            throw new DomainException(
                409,
                "A worker or live job lease remains. Stop workers and wait 30 seconds before"
                    + " rotation.");
          db.exec("delete from spring_session");
          audit.record(
              null,
              null,
              "encryption.rotation.started",
              null,
              "Active key ID " + secrets.activeKeyId() + "; sessions revoked");
        });
    long deadline = System.nanoTime() + 120_000_000_000L;
    do {
      Integer changed = tx.execute(s -> batch());
      if (changed != null && changed == 0) {
        tx.executeWithoutResult(
            s ->
                audit.record(
                    null,
                    null,
                    "encryption.rotation.completed",
                    null,
                    "All retained application ciphertext uses key " + secrets.activeKeyId()));
        return;
      }
    } while (System.nanoTime() < deadline);
    throw new DomainException(
        409,
        "Rotation reached its two-minute budget. Progress is committed; rerun with the same key"
            + " ring to continue.");
  }

  private int batch() {
    db.query("select pg_advisory_xact_lock(hashtextextended('mprp_encryption_rotation',0))");
    String prefix = "aead2:" + secrets.activeKeyId() + ":";
    int length = prefix.length(), changed = 0;
    for (var row :
        db.query(
            "select id,totp_cipher from app_user where totp_cipher is not null and"
                + " left(totp_cipher,?)<>? order by id limit 100 for update",
            length,
            prefix)) {
      UUID id = Db.uuid(row, "id");
      db.exec(
          "update app_user set totp_cipher=? where id=?",
          secrets.encrypt(
              secrets.decrypt(row.get("totp_cipher").toString(), "totp:" + id), "totp:" + id),
          id);
      changed++;
    }
    for (var row :
        db.query(
            "select id,encrypted_body from account_mail where encrypted_body<>'' and"
                + " left(encrypted_body,?)<>? order by id limit 20 for update",
            length,
            prefix)) {
      UUID id = Db.uuid(row, "id");
      db.exec(
          "update account_mail set encrypted_body=? where id=?",
          secrets.encrypt(
              secrets.decrypt(row.get("encrypted_body").toString(), "mail:" + id), "mail:" + id),
          id);
      changed++;
    }
    for (var row :
        db.query(
            "select id,content from report_export where content is not null and"
                + " left(encode(content,'escape'),?)<>? order by id limit 1 for update",
            length,
            prefix)) {
      UUID id = Db.uuid(row, "id");
      String cipher = new String((byte[]) row.get("content"), StandardCharsets.UTF_8);
      db.exec(
          "update report_export set content=? where id=?",
          secrets
              .encrypt(secrets.decrypt(cipher, "export:" + id), "export:" + id)
              .getBytes(StandardCharsets.UTF_8),
          id);
      changed++;
    }
    for (var row :
        db.query(
            "select id,content_cipher,parsed_cipher from statement_import where (content_cipher is"
                + " not null and left(encode(content_cipher,'escape'),?)<>?) or (parsed_cipher is"
                + " not null and left(encode(parsed_cipher,'escape'),?)<>?) order by id limit 1 for"
                + " update",
            length,
            prefix,
            length,
            prefix)) {
      UUID id = Db.uuid(row, "id");
      byte[] content =
          row.get("content_cipher") == null
              ? null
              : secrets.encryptBytes(
                  secrets.decryptBytes((byte[]) row.get("content_cipher"), "statement:" + id),
                  "statement:" + id);
      byte[] parsed =
          row.get("parsed_cipher") == null
              ? null
              : secrets.encryptBytes(
                  secrets.decryptBytes((byte[]) row.get("parsed_cipher"), "statement:" + id),
                  "statement:" + id);
      db.exec(
          "update statement_import set content_cipher=?,parsed_cipher=? where id=?",
          content,
          parsed,
          id);
      changed++;
    }
    return changed;
  }
}
