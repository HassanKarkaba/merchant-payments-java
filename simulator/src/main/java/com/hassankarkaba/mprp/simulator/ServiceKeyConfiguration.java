package com.hassankarkaba.mprp.simulator;

import com.hassankarkaba.mprp.protocol.ServiceKeys;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class ServiceKeyConfiguration {
  @Bean
  ServiceKeys serviceKeys(Environment e) {
    return ServiceKeys.from(
        e.getProperty("mprp.key-id", "local-v1"),
        e.getRequiredProperty("mprp.service-secret"),
        e.getRequiredProperty("mprp.service-key-active-from"),
        e.getRequiredProperty("mprp.service-key-expires-at"),
        e.getProperty("mprp.previous-service-key-id", ""),
        e.getProperty("mprp.previous-service-secret", ""),
        e.getProperty("mprp.previous-service-key-active-from", ""),
        e.getProperty("mprp.previous-service-key-expires-at", ""));
  }
}
