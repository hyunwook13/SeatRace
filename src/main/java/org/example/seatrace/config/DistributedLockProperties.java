package org.example.seatrace.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "reservation.lock")
public class DistributedLockProperties {

  private boolean enabled = false;
  private long waitTimeoutMs = 200;
  private long leaseMs = 1000;
  private long retryDelayMs = 20;
  private int maxRetry = 10;
}
