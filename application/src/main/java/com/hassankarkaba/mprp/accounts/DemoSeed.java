package com.hassankarkaba.mprp.accounts;

import com.hassankarkaba.mprp.operations.Db;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoSeed {
  private final Db db;
  private final AccountService accounts;
  private final SecretCodec secrets;

  public DemoSeed(Db db, AccountService accounts, SecretCodec secrets) {
    this.db = db;
    this.accounts = accounts;
    this.secrets = secrets;
  }

  @Transactional
  public void seed(Path destination) throws Exception {
    db.query("select pg_advisory_xact_lock(hashtextextended('mprp_demo_seed',0))");
    if (db.scalar(
            "select count(*) from app_user where email"
                + " in('owner@example.test','owner2@example.test','finance@example.test','analyst@example.test','operations@example.test','reconciler@example.test','approver@example.test','auditor@example.test')",
            Long.class)
        > 0) {
      if (Files.exists(destination)) return;
      throw new IllegalStateException(
          "Demo identities already exist. Refusing to replace their credentials or regenerate a"
              + " lost access file.");
    }
    if (Files.exists(destination))
      throw new IllegalStateException("Refusing to overwrite an existing demo access file.");
    UUID merchant = UUID.randomUUID();
    db.exec(
        "insert into merchant(id,name,status,provider_account_id,bank_account_id) values"
            + " (?,'Juniper Goods','ACTIVE',?,?)",
        merchant,
        merchant,
        merchant);
    StringBuilder access =
        new StringBuilder(
            "LOCAL FICTIONAL SANDBOX ONLY\nMerchant: "
                + merchant
                + "\n"
                + "TOTP uses 6 digits, SHA1, 30 seconds. Never put this private file in source"
                + " delivery.\n\n");
    String[][] identities = {
      {"owner", "OWNER", ""},
      {"owner2", "OWNER", ""},
      {"finance", "FINANCE", ""},
      {"analyst", "ANALYST", ""},
      {"operations", "", "OPERATIONS"},
      {"reconciler", "", "RECONCILIATION"},
      {"approver", "", "FINANCE_APPROVER"},
      {"auditor", "", "AUDITOR"}
    };
    for (String[] identity : identities) {
      UUID user = UUID.randomUUID();
      String email = identity[0] + "@example.test",
          password = "Demo!" + SecretCodec.randomToken(),
          seed = SecretCodec.newTotpSeed();
      db.exec(
          "insert into"
              + " app_user(id,email,password_hash,verified,totp_cipher,totp_confirmed,platform_role)"
              + " values (?,?,?,true,?,true,?)",
          user,
          email,
          accounts.passwords().encode(password),
          secrets.encrypt(seed, "totp:" + user),
          identity[2].isEmpty() ? null : identity[2]);
      if (!identity[1].isEmpty())
        db.exec(
            "insert into membership(id,user_id,merchant_id,role) values (?,?,?,?)",
            UUID.randomUUID(),
            user,
            merchant,
            identity[1]);
      access
          .append(email)
          .append("\nPassword: ")
          .append(password)
          .append("\nTOTP seed: ")
          .append(seed)
          .append("\nRole: ")
          .append(identity[1].isEmpty() ? identity[2] : identity[1])
          .append("\n\n");
      accounts.issueRecoveryCodes(user);
      accounts.event(user, merchant, "DEMO_USER_SEEDED");
    }
    if (destination.getParent() != null) {
      Files.createDirectories(destination.getParent());
      Files.setPosixFilePermissions(
          destination.getParent(), PosixFilePermissions.fromString("rwx------"));
    }
    Files.writeString(
        destination,
        access,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);
    Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rw-------"));
  }
}
