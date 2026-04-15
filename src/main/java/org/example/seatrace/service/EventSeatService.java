package org.example.seatrace.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.dto.seat.EventSeatSummary;
import org.example.seatrace.entity.Event;
import org.example.seatrace.exception.RedisUnavailableException;
import org.example.seatrace.repository.EventRepository;
import org.example.seatrace.repository.EventSeatRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventSeatService {

  private static final Duration CACHE_TTL = Duration.ofMinutes(5);

  private final EventRepository eventRepository;
  private final EventSeatRepository eventSeatRepository;
  private final EventSeatRedisCache eventSeatRedisCache;
  private final ObjectMapper objectMapper;

  private final ConcurrentHashMap<Long, CacheEntry> localCache = new ConcurrentHashMap<>();

  @Transactional(readOnly = true)
  public List<EventSeatSummary> listSeats(Long eventId) {
    // 1) Redis 캐시 (정상 시)
    try {
      String json = eventSeatRedisCache.get(eventId);
      if (json != null && !json.isBlank()) {
        List<EventSeatSummary> seats =
            objectMapper.readValue(json, new TypeReference<>() {});
        putLocal(eventId, seats);
        return seats;
      }
    } catch (RedisUnavailableException ex) {
      // Redis 장애 시 로컬/DB로 폴백
      log.warn("Redis unavailable. Fallback to local/DB for eventId={}", eventId);
    } catch (Exception ex) {
      log.warn("Redis seat cache parse/read 실패. eventId={}", eventId, ex);
    }

    // 2) 로컬 스냅샷/캐시
    List<EventSeatSummary> local = getLocalIfFresh(eventId);
    if (local != null) {
      return local;
    }

    // 3) DB
    List<EventSeatSummary> fromDb = loadFromDb(eventId);
    putLocal(eventId, fromDb);

    // best-effort: Redis write-back
    try {
      eventSeatRedisCache.set(eventId, objectMapper.writeValueAsString(fromDb), CACHE_TTL);
    } catch (Exception ex) {
      // ignore
    }

    return fromDb;
  }

  /**
   * 1분마다 Redis 캐시를 로컬로 스냅샷(갱신)한다.
   * - Redis 장애면 조용히 스킵
   * - 로컬 만료된 엔트리는 제거
   */
  @Scheduled(fixedDelay = 60_000)
  public void refreshLocalSnapshotFromRedis() {
    long now = System.currentTimeMillis();
    for (Map.Entry<Long, CacheEntry> entry : localCache.entrySet()) {
      Long eventId = entry.getKey();
      CacheEntry cacheEntry = entry.getValue();
      if (cacheEntry == null) {
        continue;
      }
      if (cacheEntry.expiresAtMillis <= now) {
        localCache.remove(eventId);
        continue;
      }

      try {
        String json = eventSeatRedisCache.get(eventId);
        if (json == null || json.isBlank()) {
          continue;
        }
        List<EventSeatSummary> seats =
            objectMapper.readValue(json, new TypeReference<>() {});
        putLocal(eventId, seats);
      } catch (Exception ex) {
        // ignore (keep local snapshot)
      }
    }
  }

  private List<EventSeatSummary> loadFromDb(Long eventId) {
    Event event = eventRepository.findById(eventId)
        .orElseThrow(() -> new IllegalArgumentException("Event not found"));
    return eventSeatRepository.findAllByEvent(event).stream()
        .map(EventSeatSummary::from)
        .toList();
  }

  private void putLocal(Long eventId, List<EventSeatSummary> seats) {
    long expiresAtMillis = System.currentTimeMillis() + CACHE_TTL.toMillis();
    localCache.put(eventId, new CacheEntry(seats, expiresAtMillis));
  }

  private List<EventSeatSummary> getLocalIfFresh(Long eventId) {
    CacheEntry entry = localCache.get(eventId);
    if (entry == null) {
      return null;
    }
    if (entry.expiresAtMillis <= System.currentTimeMillis()) {
      localCache.remove(eventId);
      return null;
    }
    return entry.value;
  }

  private record CacheEntry(List<EventSeatSummary> value, long expiresAtMillis) {
  }
}
