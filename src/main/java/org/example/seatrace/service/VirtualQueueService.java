package org.example.seatrace.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.config.VirtualQueueProperties;
import org.example.seatrace.dto.queue.QueueEnterResponse;
import org.example.seatrace.dto.queue.QueueStatusResponse;
import org.example.seatrace.exception.RedisUnavailableException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualQueueService {

  private static final String WAIT_KEY = "seat-race:queue:wait:%d";
  private static final String ACTIVE_KEY = "seat-race:queue:active:%d";
  private static final String GATE_KEY = "seat-race:queue:gate:%d:%d";
  private static final String TOKEN_KEY = "seat-race:queue:token:%s";
  private static final String USER_TOKEN_KEY = "seat-race:queue:user-token:%d:%d";
  private static final RedisOperationFeature REDIS_FEATURE = RedisOperationFeature.QUEUE_ADMISSION;

  private final RedisFacade redisFacade;
  private final VirtualQueueProperties virtualQueueProperties;
  private final MeterRegistry meterRegistry;
  private final Set<Long> knownEventIds = ConcurrentHashMap.newKeySet();
  private final ConcurrentMap<String, AdmissionTokenEntry> admissionTokenCache = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AdmissionTokenEntry> userAdmissionCache = new ConcurrentHashMap<>();

  public QueueEnterResponse enterOrWait(Long eventId, Long userId) {
    if (!virtualQueueProperties.isEnabled()) {
      return admittedResponseWithoutRedis(eventId, 0);
    }

    knownEventIds.add(eventId);

    try {
      if (isActive(eventId, userId)) {
        return admittedResponse(eventId, userId, 0);
      }

      ensureWaiting(eventId, userId);

      long position = getPosition(eventId, userId);
      boolean admitted = isActive(eventId, userId);
      if (admitted) {
        return admittedResponse(eventId, userId, 0);
      }

      Counter.builder("seatrace.queue.wait.total")
          .description("Total number of wait queue responses")
          .register(meterRegistry)
          .increment();

      return QueueEnterResponse.builder()
          .admitted(false)
          .position(position)
          .activeCount(getActiveCount(eventId))
          .waitCount(getWaitCount(eventId))
          .tps(virtualQueueProperties.getTps())
          .activeLimit(virtualQueueProperties.getActiveLimit())
          .estimatedWaitMillis(estimateWaitMillis(position))
          .admissionToken(null)
          .admissionExpiresAtMillis(0)
          .build();
    } catch (Exception ex) {
      throw redisUnavailable("queue admission", eventId, userId, ex);
    }
  }

  public QueueStatusResponse status(Long eventId, Long userId) {
    if (!virtualQueueProperties.isEnabled()) {
      return QueueStatusResponse.builder()
          .admitted(true)
          .waiting(false)
          .position(0)
          .activeCount(0)
          .waitCount(0)
          .tps(virtualQueueProperties.getTps())
          .activeLimit(virtualQueueProperties.getActiveLimit())
          .estimatedWaitMillis(0)
          .admissionToken(null)
          .admissionExpiresAtMillis(0)
          .build();
    }

    knownEventIds.add(eventId);

    try {
      boolean admitted = isActive(eventId, userId);
      long position = admitted ? 0 : getPosition(eventId, userId);
      boolean waiting = !admitted && position >= 0;
      AdmissionTokenEntry tokenEntry = admitted ? issueAdmissionToken(eventId, userId) : null;

      return QueueStatusResponse.builder()
          .admitted(admitted)
          .waiting(waiting)
          .position(position)
          .activeCount(getActiveCount(eventId))
          .waitCount(getWaitCount(eventId))
          .tps(virtualQueueProperties.getTps())
          .activeLimit(virtualQueueProperties.getActiveLimit())
          .estimatedWaitMillis(admitted ? 0 : estimateWaitMillis(position))
          .admissionToken(tokenEntry == null ? null : tokenEntry.token())
          .admissionExpiresAtMillis(tokenEntry == null ? 0 : tokenEntry.expiresAtMillis())
          .build();
    } catch (Exception ex) {
      throw redisUnavailable("queue status", eventId, userId, ex);
    }
  }

  /**
   * [보호 API 가드 레이어]
   * 최전방에서 0ms 수준의 초고속 입구 컷 및 가비지 프리 검증을 수행합니다.
   */
  public boolean isAdmitted(Long eventId, Long userId, String admissionToken) {
    if (!virtualQueueProperties.isEnabled()) {
      return true;
    }
    if (admissionToken == null || admissionToken.isBlank()) {
      return false;
    }

    long now = System.currentTimeMillis();

    // [1단계] 로컬 캐시(스프링 메모리) 선에서 검증 -> Heap 오염 0건
    AdmissionTokenEntry cached = admissionTokenCache.get(admissionToken);
    if (isValid(cached, eventId, userId, now)) {
      return true;
    }
    admissionTokenCache.remove(admissionToken, cached);

    // [2단계] 로컬 캐시 Miss 시, 딱 1번 레디스 조회하여 고속 파싱 진행
    try {
      String redisValue = redisFacade.get(REDIS_FEATURE, tokenKey(admissionToken));
      if (redisValue == null || redisValue.isBlank()) {
        return false;
      }

      // 가독성을 위해 분리한 "Garbage-Free 검증 메서드" 호출 -> 힙 할당 0건
      if (!validateRawTokenWithoutAllocation(redisValue, eventId, userId, now)) {
        return false;
      }

      // 실제로 인증 통과한 유저만 '딱 1번' 영구 객체로 전환하여 로컬 캐시에 보관
      long expiresAt = extractExpiresAt(redisValue);
      cacheAdmission(new AdmissionTokenEntry(admissionToken, eventId, userId, expiresAt));
      return true;

    } catch (Exception ex) {
      throw redisUnavailable("queue token validation", eventId, userId, ex);
    }
  }

  public QueueEnterResponse notAdmittedResponse(Long eventId) {
    return QueueEnterResponse.builder()
        .admitted(false)
        .position(-1)
        .activeCount(-1)
        .waitCount(-1)
        .tps(virtualQueueProperties.getTps())
        .activeLimit(virtualQueueProperties.getActiveLimit())
        .estimatedWaitMillis(0)
        .admissionToken(null)
        .admissionExpiresAtMillis(0)
        .build();
  }

  @Scheduled(fixedDelayString = "#{@virtualQueueProperties.advanceDelayMs}")
  public void advanceKnownQueues() {
    if (!virtualQueueProperties.isEnabled()) {
      return;
    }

    evictExpiredLocalAdmissions();
    for (Long eventId : knownEventIds) {
      try {
        cleanupExpiredActive(eventId);
        advanceQueue(eventId);
      } catch (Exception ex) {
        log.warn("VirtualQueue scheduler skipped event due to Redis failure: eventId={}", eventId, ex);
      }
    }
  }

  private QueueEnterResponse admittedResponse(Long eventId, Long userId, long position) {
    Counter.builder("seatrace.queue.admit.total")
        .description("Total number of admitted users")
        .register(meterRegistry)
        .increment();

    AdmissionTokenEntry tokenEntry = issueAdmissionToken(eventId, userId);

    return QueueEnterResponse.builder()
        .admitted(true)
        .position(position)
        .activeCount(getActiveCount(eventId))
        .waitCount(getWaitCount(eventId))
        .tps(virtualQueueProperties.getTps())
        .activeLimit(virtualQueueProperties.getActiveLimit())
        .estimatedWaitMillis(0)
        .admissionToken(tokenEntry.token())
        .admissionExpiresAtMillis(tokenEntry.expiresAtMillis())
        .build();
  }

  private QueueEnterResponse admittedResponseWithoutRedis(Long eventId, long position) {
    Counter.builder("seatrace.queue.admit.total")
        .description("Total number of admitted users")
        .register(meterRegistry)
        .increment();

    return QueueEnterResponse.builder()
        .admitted(true)
        .position(position)
        .activeCount(0)
        .waitCount(0)
        .tps(virtualQueueProperties.getTps())
        .activeLimit(virtualQueueProperties.getActiveLimit())
        .estimatedWaitMillis(0)
        .admissionToken(null)
        .admissionExpiresAtMillis(0)
        .build();
  }

  private AdmissionTokenEntry issueAdmissionToken(Long eventId, Long userId) {
    long now = System.currentTimeMillis();
    String userKey = userTokenKey(eventId, userId);
    AdmissionTokenEntry cached = userAdmissionCache.get(userKey);
    if (isValid(cached, eventId, userId, now)) {
      return cached;
    }
    userAdmissionCache.remove(userKey, cached);

    try {
      String existingToken = redisFacade.get(REDIS_FEATURE, userKey);
      if (existingToken != null && !existingToken.isBlank()) {
        String redisValue = redisFacade.get(REDIS_FEATURE, tokenKey(existingToken));

        // 토큰 발급 로직에서도 일회성 파싱을 가비지 프리 전용 메서드로 안전하게 검증
        if (validateRawTokenWithoutAllocation(redisValue, eventId, userId, now)) {
          long expiresAt = extractExpiresAt(redisValue);
          AdmissionTokenEntry redisEntry = new AdmissionTokenEntry(existingToken, eventId, userId, expiresAt);
          cacheAdmission(redisEntry);
          return redisEntry;
        }
      }
    } catch (Exception ex) {
      log.warn("VirtualQueue token reuse skipped due to Redis failure: eventId={}, userId={}", eventId, userId, ex);
    }

    AdmissionTokenEntry created = new AdmissionTokenEntry(
        UUID.randomUUID().toString(),
        eventId,
        userId,
        now + tokenTtl().toMillis()
    );
    cacheAdmission(created);
    try {
      redisFacade.set(REDIS_FEATURE, tokenKey(created.token()), serializeTokenEntry(created), tokenTtl());
      redisFacade.set(REDIS_FEATURE, userKey, created.token(), tokenTtl());
    } catch (Exception ex) {
      log.warn("VirtualQueue token stored only locally due to Redis failure: eventId={}, userId={}", eventId, userId, ex);
    }
    return created;
  }

  private void cacheAdmission(AdmissionTokenEntry entry) {
    admissionTokenCache.put(entry.token(), entry);
    userAdmissionCache.put(userTokenKey(entry.eventId(), entry.userId()), entry);
  }

  private void evictExpiredLocalAdmissions() {
    long now = System.currentTimeMillis();
    admissionTokenCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    userAdmissionCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
  }

  private boolean isValid(AdmissionTokenEntry entry, Long eventId, Long userId, long now) {
    return entry != null
        && entry.eventId().equals(eventId)
        && entry.userId().equals(userId)
        && entry.expiresAtMillis() > now;
  }

  private String serializeTokenEntry(AdmissionTokenEntry entry) {
    return entry.eventId() + ":" + entry.userId() + ":" + entry.expiresAtMillis();
  }

  private Duration tokenTtl() {
    return Duration.ofSeconds(Math.max(virtualQueueProperties.getActiveTtlSeconds(), 1));
  }

  private void ensureWaiting(Long eventId, Long userId) {
    String key = waitKey(eventId);
    String member = userId.toString();
    Double score = redisFacade.zScore(REDIS_FEATURE, key, member);
    if (score == null) {
      redisFacade.zAdd(REDIS_FEATURE, key, member, System.currentTimeMillis());
      Counter.builder("seatrace.queue.enter.total")
          .description("Total number of users entered into queue")
          .register(meterRegistry)
          .increment();
    }
  }

  private void advanceQueue(Long eventId) {
    int maxAdvance = virtualQueueProperties.getMaxAdvancePerCall();
    int activeLimit = virtualQueueProperties.getActiveLimit();

    while (maxAdvance-- > 0) {
      cleanupExpiredActive(eventId);

      if (getActiveCount(eventId) >= activeLimit) {
        break;
      }

      Set<String> head = redisFacade.zRange(REDIS_FEATURE, waitKey(eventId), 0, 0);
      if (head == null || head.isEmpty()) {
        break;
      }

      if (!gateAllows(eventId)) {
        break;
      }

      String member = head.iterator().next();
      redisFacade.zRemove(REDIS_FEATURE, waitKey(eventId), member);
      redisFacade.zAdd(REDIS_FEATURE, activeKey(eventId), member, System.currentTimeMillis());

      Counter.builder("seatrace.queue.advance.total")
          .description("Total number of users advanced from wait to active")
          .register(meterRegistry)
          .increment();
    }
  }

  private boolean gateAllows(Long eventId) {
    long epochSecond = System.currentTimeMillis() / 1000;
    String key = GATE_KEY.formatted(eventId, epochSecond);
    Long count = redisFacade.increment(REDIS_FEATURE, key);
    if (count != null && count == 1L) {
      redisFacade.expire(REDIS_FEATURE, key, Duration.ofSeconds(2));
    }
    return count != null && count <= virtualQueueProperties.getTps();
  }

  private void cleanupExpiredActive(Long eventId) {
    long cutoff = System.currentTimeMillis() - (virtualQueueProperties.getActiveTtlSeconds() * 1000L);
    redisFacade.zRemoveRangeByScore(REDIS_FEATURE, activeKey(eventId), 0, cutoff);
  }

  private boolean isActive(Long eventId, Long userId) {
    return redisFacade.zScore(REDIS_FEATURE, activeKey(eventId), userId.toString()) != null;
  }

  private long getPosition(Long eventId, Long userId) {
    Long rank = redisFacade.zRank(REDIS_FEATURE, waitKey(eventId), userId.toString());
    return rank == null ? -1 : rank + 1;
  }

  private long getActiveCount(Long eventId) {
    Long count = redisFacade.zSize(REDIS_FEATURE, activeKey(eventId));
    return count == null ? 0 : count;
  }

  private long getWaitCount(Long eventId) {
    Long count = redisFacade.zSize(REDIS_FEATURE, waitKey(eventId));
    return count == null ? 0 : count;
  }

  private long estimateWaitMillis(long position) {
    int tps = Math.max(virtualQueueProperties.getTps(), 1);
    if (position <= 0) {
      return 0;
    }
    return (position * 1000L) / tps;
  }

  private RedisUnavailableException redisUnavailable(
      String operation,
      Long eventId,
      Long userId,
      Exception cause
  ) {
    log.warn("VirtualQueue {} unavailable: eventId={}, userId={}", operation, eventId, userId, cause);
    return new RedisUnavailableException("대기열을 일시적으로 처리할 수 없습니다.", cause);
  }

  private String waitKey(Long eventId) {
    return WAIT_KEY.formatted(eventId);
  }

  private String activeKey(Long eventId) {
    return ACTIVE_KEY.formatted(eventId);
  }

  private String tokenKey(String token) {
    return TOKEN_KEY.formatted(token);
  }

  private String userTokenKey(Long eventId, Long userId) {
    return USER_TOKEN_KEY.formatted(eventId, userId);
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 🔒 가비지 프리(Garbage-Free) 전용 고속 검증 및 추출 메서드 구역
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * split() 이나 껍데기 객체 생성 없이 오직 스택 영역의 기본형 프리미티브 값으로만 고속 검증합니다.
   * 힙 메모리 할당을 전혀 일으키지 않아 GC 오염이 발생하지 않습니다.
   */
  private boolean validateRawTokenWithoutAllocation(String value, Long eventId, Long userId, long now) {
    if (value == null || value.isBlank()) {
      return false;
    }

    int firstColon = value.indexOf(':');
    int secondColon = value.indexOf(':', firstColon + 1);
    if (firstColon == -1 || secondColon == -1) {
      return false;
    }

    try {
      long parsedEventId = Long.parseLong(value.substring(0, firstColon));
      long parsedUserId = Long.parseLong(value.substring(firstColon + 1, secondColon));
      long parsedExpiresAt = Long.parseLong(value.substring(secondColon + 1));

      return parsedEventId == eventId && parsedUserId == userId && parsedExpiresAt > now;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  /**
   * 문자열에서 만료 시간 필드만 가볍게 숫자로 추출해 냅니다. (객체 생성 제로)
   */
  private long extractExpiresAt(String value) {
    int firstColon = value.indexOf(':');
    int secondColon = value.indexOf(':', firstColon + 1);
    return Long.parseLong(value.substring(secondColon + 1));
  }

  private record AdmissionTokenEntry(
      String token,
      Long eventId,
      Long userId,
      long expiresAtMillis
  ) {
  }
}
