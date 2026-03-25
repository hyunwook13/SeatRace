package org.example.seatrace.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "reservation.hold")
public class ReservationHoldProperties {

  private long ttlSeconds = 10;
  private long cleanupDelayMs = 3000;
}
