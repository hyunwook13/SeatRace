package org.example.seatrace.service;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EventSeatRedisCache {

  private static final String KEY_FMT = "seat-race:cache:event-seats:%d:v1";

  private final RedisFacade redisFacade;

  public String get(Long eventId) {
    return redisFacade.get(key(eventId));
  }

  public void set(Long eventId, String json, Duration ttl) {
    redisFacade.set(key(eventId), json, ttl);
  }

  private String key(Long eventId) {
    return KEY_FMT.formatted(eventId);
  }
}
