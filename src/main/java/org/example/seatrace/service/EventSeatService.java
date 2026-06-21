package org.example.seatrace.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.config.EventSeatCacheProperties;
import org.example.seatrace.dto.seat.EventSeatSummary;
import org.example.seatrace.entity.Event;
import org.example.seatrace.repository.EventRepository;
import org.example.seatrace.repository.EventSeatRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class EventSeatService {

  private static final Duration DB_COLLAPSE_WAIT = Duration.ofSeconds(2);

  private final EventRepository eventRepository;
  private final EventSeatRepository eventSeatRepository;
  private final EventSeatRedisCache eventSeatRedisCache;
  private final ObjectMapper objectMapper;
  private final EventSeatCacheProperties eventSeatCacheProperties;
  private final Counter dbLoadCounter;

  private final ConcurrentHashMap<Long, CacheEntry> localCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, CompletableFuture<List<EventSeatSummary>>> inFlightDbLoads =
      new ConcurrentHashMap<>();

  public EventSeatService(
      EventRepository eventRepository,
      EventSeatRepository eventSeatRepository,
      EventSeatRedisCache eventSeatRedisCache,
      ObjectMapper objectMapper,
      EventSeatCacheProperties eventSeatCacheProperties,
      MeterRegistry meterRegistry
  ) {
    this.eventRepository = eventRepository;
    this.eventSeatRepository = eventSeatRepository;
    this.eventSeatRedisCache = eventSeatRedisCache;
    this.objectMapper = objectMapper;
    this.eventSeatCacheProperties = eventSeatCacheProperties;
    this.dbLoadCounter = Counter.builder("seatrace.event_seats.db_load")
        .description("Number of DB loads for event seat list (after cache miss/fallback)")
        .register(meterRegistry);
  }

  @Transactional(readOnly = true)
  public List<EventSeatSummary> listSeats(Long eventId) {
    // 1) Redis 캐시 (정상 시 / CB fallback 시 null)
    try {
      String json = eventSeatRedisCache.get(eventId);
      if (json != null && !json.isBlank()) {
        List<EventSeatSummary> seats =
            objectMapper.readValue(json, new TypeReference<>() {});
        putLocal(eventId, seats);
        return seats;
      }
    } catch (Exception ex) {
      log.warn("Redis seat cache parse/read 실패. eventId={}", eventId, ex);
    }

    // 2) 로컬 스냅샷/캐시
    List<EventSeatSummary> local = getLocalIfFresh(eventId);
    if (local != null) {
      return local;
    }

    // 3) DB (request collapsing)
    List<EventSeatSummary> fromDb = loadFromDbCollapsed(eventId);
    putLocal(eventId, fromDb);

    // best-effort: Redis write-back
    try {
      eventSeatRedisCache.set(
          eventId,
          objectMapper.writeValueAsString(fromDb),
          Duration.ofSeconds(eventSeatCacheProperties.getRedisTtlSeconds())
      );
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

  private List<EventSeatSummary> loadFromDbCollapsed(Long eventId) {
    CompletableFuture<List<EventSeatSummary>> newFuture = new CompletableFuture<>();
    CompletableFuture<List<EventSeatSummary>> existing = inFlightDbLoads.putIfAbsent(eventId, newFuture);

    if (existing == null) {
      try {
        List<EventSeatSummary> seats = loadFromDb(eventId);
        newFuture.complete(seats);
        return seats;
      } catch (Exception ex) {
        newFuture.completeExceptionally(ex);
        throw ex;
      } finally {
        inFlightDbLoads.remove(eventId, newFuture);
      }
    }

    try {
      return existing.get(DB_COLLAPSE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException ex) {
      log.warn("DB collapse wait timeout. fallback to direct DB load: eventId={}", eventId);
      return loadFromDb(eventId);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return loadFromDb(eventId);
    } catch (ExecutionException ex) {
      log.warn("DB collapse failed. fallback to direct DB load: eventId={}", eventId, ex.getCause());
      return loadFromDb(eventId);
    }
  }

  private List<EventSeatSummary> loadFromDb(Long eventId) {
    dbLoadCounter.increment();
    Event event = eventRepository.findById(eventId)
        .orElseThrow(() -> new IllegalArgumentException("Event not found"));
    return eventSeatRepository.findAllByEvent(event).stream()
        .map(EventSeatSummary::from)
        .toList();
  }

  private void putLocal(Long eventId, List<EventSeatSummary> seats) {
    long expiresAtMillis = System.currentTimeMillis() + eventSeatCacheProperties.getLocalTtlMs();
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
