package org.example.seatrace.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "redis.resilience")
public class RedisResilienceProperties {

  /** Disables both Circuit Breaker decoration and cache bypass for an A/B test. */
  private boolean circuitBreakerEnabled = true;
}
