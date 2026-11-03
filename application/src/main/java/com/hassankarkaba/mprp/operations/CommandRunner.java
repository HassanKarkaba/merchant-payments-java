package com.hassankarkaba.mprp.operations;

import com.hassankarkaba.mprp.accounts.DemoSeed;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
public class CommandRunner implements ApplicationRunner {
  private final String command, path;
  private final DemoSeed seed;
  private final PurgeService purge;
  private final com.hassankarkaba.mprp.accounts.AccountsLocalAdmin accountsAdmin;
  private final org.springframework.core.env.Environment env;
  private final String purgeId;
  private final com.hassankarkaba.mprp.accounts.EncryptionRotation rotation;

  public CommandRunner(
      DemoSeed seed,
      PurgeService purge,
      com.hassankarkaba.mprp.accounts.AccountsLocalAdmin accountsAdmin,
      org.springframework.core.env.Environment env,
      com.hassankarkaba.mprp.accounts.EncryptionRotation rotation,
      @Value("${MPRP_PURGE_ID:}") String purgeId,
      @Value("${mprp.command:serve}") String command,
      @Value("${MPRP_DEMO_ACCESS_PATH:/data/demo-access.txt}") String path) {
    this.seed = seed;
    this.accountsAdmin = accountsAdmin;
    this.env = env;
    this.rotation = rotation;
    this.purge = purge;
    this.purgeId = purgeId;
    this.command = command;
    this.path = path;
  }

  public void run(ApplicationArguments args) throws Exception {
    if (command.equals("seed-demo")) seed.seed(Path.of(path));
    else if (command.equals("bootstrap-staff"))
      accountsAdmin.bootstrap(
          env.getRequiredProperty("MPRP_ACCOUNT_EMAIL"),
          env.getRequiredProperty("MPRP_ACCOUNT_ROLE"),
          java.util.UUID.fromString(env.getRequiredProperty("MPRP_OPERATOR_ID")),
          env.getRequiredProperty("MPRP_ACCOUNT_REASON"),
          Path.of(env.getRequiredProperty("MPRP_NEW_PASSWORD_FILE")),
          Path.of(env.getProperty("MPRP_OPERATOR_PASSWORD_FILE", "/run/not-provided")),
          Path.of(env.getProperty("MPRP_OPERATOR_TOTP_FILE", "/run/not-provided")));
    else if (command.equals("recover-mfa"))
      accountsAdmin.recoverMfa(
          env.getRequiredProperty("MPRP_ACCOUNT_EMAIL"),
          java.util.UUID.fromString(env.getRequiredProperty("MPRP_OPERATOR_ID")),
          env.getRequiredProperty("MPRP_ACCOUNT_REASON"),
          Path.of(env.getRequiredProperty("MPRP_OPERATOR_PASSWORD_FILE")),
          Path.of(env.getRequiredProperty("MPRP_OPERATOR_TOTP_FILE")),
          env.getProperty("MPRP_ACCOUNT_OWNER_RECOVERY", Boolean.class, false));
    else if (command.equals("rotate-encryption")) rotation.rotate();
    else if (command.equals("purge-retire")) purge.retire(java.util.UUID.fromString(purgeId));
    else if (command.equals("purge-execute")) purge.execute(java.util.UUID.fromString(purgeId));
    else if (!command.equals("serve") && !command.equals("migrate"))
      throw new IllegalArgumentException("Unknown local command");
  }
}
