package org.example.seatrace.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "event-seat.cache")
public class EventSeatCacheProperties {

  /**
   * Local in-memory cache TTL used to reduce DB stampede when Redis is unavailable or missed.
   */
  private long localTtlMs = 300_000; // 5 minutes

  /**
   * Redis cache TTL for seat list snapshots.
   */
  private long redisTtlSeconds = 300; // 5 minutes
}

