package com.hassankarkaba.mprp.operations;

import org.springframework.context.annotation.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaFailureConfiguration {
  @Bean
  public DefaultErrorHandler durableKafkaErrorHandler() {
    return new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
  }
}
