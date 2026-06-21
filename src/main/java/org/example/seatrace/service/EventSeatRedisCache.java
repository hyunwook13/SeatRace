package org.example.seatrace.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventSeatRedisCache {

  private static final String KEY_FMT = "seat-race:cache:event-seats:%d:v1";
  private static final long DEGRADED_COOLDOWN_MILLIS = 5_000;

  private final RedisFacade redisFacade;
  private final AtomicLong degradedUntilMillis = new AtomicLong(0);

  @CircuitBreaker(name = "seatCache", fallbackMethod = "getFallback")
  public String get(Long eventId) {
    if (isDegraded()) {
      return null;
    }
    return redisFacade.get(key(eventId));
  }

  @SuppressWarnings("unused")
  private String getFallback(Long eventId, Throwable ex) {
    markDegraded();
    log.warn("EventSeatRedisCache.get fallback: eventId={}, message={}", eventId, ex.getMessage());
    return null;
  }

  @CircuitBreaker(name = "seatCache", fallbackMethod = "setFallback")
  public void set(Long eventId, String json, Duration ttl) {
    if (isDegraded()) {
      return;
    }
    redisFacade.set(key(eventId), json, ttl);
  }

  @SuppressWarnings("unused")
  private void setFallback(Long eventId, String json, Duration ttl, Throwable ex) {
    markDegraded();
    log.warn("EventSeatRedisCache.set fallback: eventId={}, message={}", eventId, ex.getMessage());
  }

  private String key(Long eventId) {
    return KEY_FMT.formatted(eventId);
  }

  private boolean isDegraded() {
    return System.currentTimeMillis() < degradedUntilMillis.get();
  }

  private void markDegraded() {
    degradedUntilMillis.set(System.currentTimeMillis() + DEGRADED_COOLDOWN_MILLIS);
  }
}
