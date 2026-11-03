package com.hassankarkaba.mprp.operations;

import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Non-secret, typed local compatibility profile; unsupported modes fail before admissions. */
@ConfigurationProperties("mprp")
public record RuntimeProfile(
    UUID environmentId,
    @DefaultValue("SANDBOX") Mode mode,
    @DefaultValue("USD") Currency currency,
    @DefaultValue("mprp_v1") String policyVersion,
    @DefaultValue("1") int schemaVersion,
    @DefaultValue("http://simulator:8181") URI simulatorUrl,
    @DefaultValue("false") boolean worker,
    @DefaultValue("serve") String command) {
  public enum Mode {
    SANDBOX
  }

  public enum Currency {
    USD
  }

  public RuntimeProfile {
    if (environmentId == null
        || mode == null
        || currency == null
        || !"mprp_v1".equals(policyVersion)
        || schemaVersion != 1)
      throw new IllegalArgumentException("Unsupported local compatibility profile");
    if (simulatorUrl == null
        || !"http".equals(simulatorUrl.getScheme())
        || !Set.of("simulator", "localhost", "127.0.0.1").contains(simulatorUrl.getHost())
        || simulatorUrl.getUserInfo() != null
        || simulatorUrl.getQuery() != null
        || simulatorUrl.getFragment() != null
        || !(simulatorUrl.getPath().isEmpty() || simulatorUrl.getPath().equals("/")))
      throw new IllegalArgumentException("Simulator must use a fixed local HTTP origin");
  }
}
