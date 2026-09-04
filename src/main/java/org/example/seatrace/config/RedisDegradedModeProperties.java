package org.example.seatrace.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Allows a read-only service mode when Redis-backed admission and locking are unavailable.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "redis.degraded-mode")
public class RedisDegradedModeProperties {

  private boolean enabled = false;
}
