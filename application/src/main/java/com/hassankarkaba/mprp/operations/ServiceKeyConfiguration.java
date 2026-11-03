package com.hassankarkaba.mprp.operations;

import com.hassankarkaba.mprp.protocol.ServiceKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class ServiceKeyConfiguration {
  @Bean
  public ServiceKeys serviceKeys(
      @Value("${MPRP_SERVICE_KEY_ID:local-v1}") String id,
      @Value("${mprp.service-secret}") String secret,
      @Value("${MPRP_SERVICE_KEY_ACTIVE_FROM}") String from,
      @Value("${MPRP_SERVICE_KEY_EXPIRES_AT}") String until,
      @Value("${MPRP_PREVIOUS_SERVICE_KEY_ID:}") String oldId,
      @Value("${MPRP_PREVIOUS_SERVICE_KEY_SECRET:}") String oldSecret,
      @Value("${MPRP_PREVIOUS_SERVICE_KEY_ACTIVE_FROM:}") String oldFrom,
      @Value("${MPRP_PREVIOUS_SERVICE_KEY_EXPIRES_AT:}") String oldUntil) {
    return ServiceKeys.from(id, secret, from, until, oldId, oldSecret, oldFrom, oldUntil);
  }
}
