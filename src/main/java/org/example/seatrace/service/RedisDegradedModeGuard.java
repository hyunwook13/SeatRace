package org.example.seatrace.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.config.RedisDegradedModeProperties;
import org.example.seatrace.exception.RedisUnavailableException;
import org.springframework.stereotype.Component;

/**
 * Degraded mode preserves read APIs but rejects mutations that require Redis-backed coordination.
 */
@Component
@RequiredArgsConstructor
public class RedisDegradedModeGuard {

  private final RedisDegradedModeProperties properties;
  private final MeterRegistry meterRegistry;

  public void requireQueueAvailable() {
    rejectIfEnabled("queue");
  }

  public void requireReservationAvailable() {
    rejectIfEnabled("reservation");
  }

  private void rejectIfEnabled(String feature) {
    if (!properties.isEnabled()) {
      return;
    }

    Counter.builder("seatrace.redis_degraded.rejected")
        .tag("feature", feature)
        .description("Requests rejected because Redis-backed coordination is unavailable")
        .register(meterRegistry)
        .increment();
    throw new RedisUnavailableException("Redis 장애 복구 중입니다. " + feature + " 요청은 잠시 후 다시 시도해주세요.");
  }
}
