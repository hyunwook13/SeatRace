package org.example.seatrace.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.config.RedisResilienceProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EventSeatRedisCache {

  private static final String KEY_FMT = "seat-race:cache:event-seats:%d:v1";
  private static final long DEGRADED_COOLDOWN_MILLIS = 5_000;

  private final RedisFacade redisFacade;
  private final RedisResilienceProperties resilienceProperties;
  private final AtomicLong degradedUntilMillis = new AtomicLong(0);
  private final Counter redisFallbackCounter;
  private final Counter redisBypassCounter;

  public EventSeatRedisCache(
      RedisFacade redisFacade,
      RedisResilienceProperties resilienceProperties,
      MeterRegistry meterRegistry
  ) {
    this.redisFacade = redisFacade;
    this.resilienceProperties = resilienceProperties;
    this.redisFallbackCounter = Counter.builder("seatrace.event_seat_cache.redis_fallback")
        .description("Redis seat-cache failures handled by the fallback path")
        .register(meterRegistry);
    this.redisBypassCounter = Counter.builder("seatrace.event_seat_cache.redis_bypass")
        .description("Seat-cache reads that bypass Redis during degraded mode")
        .register(meterRegistry);
  }

  public String get(Long eventId) {
    if (isDegraded()) {
      redisBypassCounter.increment();
      return null;
    }
    try {
      return redisFacade.get(RedisOperationFeature.SEAT_CACHE, key(eventId));
    } catch (RuntimeException ex) {
      return getFallback(eventId, ex);
    }
  }

  private String getFallback(Long eventId, Throwable ex) {
    redisFallbackCounter.increment();
    markDegraded();
    log.warn("EventSeatRedisCache.get fallback: eventId={}, message={}", eventId, ex.getMessage());
    return null;
  }

  public void set(Long eventId, String json, Duration ttl) {
    if (isDegraded()) {
      return;
    }
    try {
      redisFacade.set(RedisOperationFeature.SEAT_CACHE, key(eventId), json, ttl);
    } catch (RuntimeException ex) {
      setFallback(eventId, json, ttl, ex);
    }
  }

  private void setFallback(Long eventId, String json, Duration ttl, Throwable ex) {
    markDegraded();
    log.warn("EventSeatRedisCache.set fallback: eventId={}, message={}", eventId, ex.getMessage());
  }

  private String key(Long eventId) {
    return KEY_FMT.formatted(eventId);
  }

  private boolean isDegraded() {
    return resilienceProperties.isCircuitBreakerEnabled()
        && System.currentTimeMillis() < degradedUntilMillis.get();
  }

  private void markDegraded() {
    if (resilienceProperties.isCircuitBreakerEnabled()) {
      degradedUntilMillis.set(System.currentTimeMillis() + DEGRADED_COOLDOWN_MILLIS);
    }
  }
}
