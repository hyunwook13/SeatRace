package org.example.seatrace.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "virtual-queue")
public class VirtualQueueProperties {

  private boolean enabled = true;
  private int tps = 500;
  private int activeLimit = 2000;
  private int activeTtlSeconds = 60;
  private int maxAdvancePerCall = 50;
}
