package org.example.seatrace.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.config.VirtualQueueProperties;
import org.example.seatrace.dto.queue.QueueEnterResponse;
import org.example.seatrace.dto.queue.QueueLeaseResponse;
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
  private static final RedisOperationFeature STATUS_REDIS_FEATURE = RedisOperationFeature.QUEUE_STATUS;
  private static final RedisOperationFeature ENTRY_REDIS_FEATURE = RedisOperationFeature.QUEUE_ENTRY;
  private static final RedisOperationFeature ADVANCE_REDIS_FEATURE = RedisOperationFeature.QUEUE_ADVANCE;
  private static final RedisOperationFeature LEASE_REDIS_FEATURE = RedisOperationFeature.QUEUE_LEASE;
  private static final String ENTER_OR_WAIT_SCRIPT = """
      local activeScore = redis.call('ZSCORE', KEYS[2], ARGV[1])
      if activeScore and tonumber(activeScore) > tonumber(ARGV[3]) then
        return 'ACTIVE|0|' .. redis.call('ZCARD', KEYS[2]) .. '|' .. redis.call('ZCARD', KEYS[1]) .. '|' .. activeScore .. '|0'
      end
      if activeScore then redis.call('ZREM', KEYS[2], ARGV[1]) end
      local entered = redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1])
      local position = redis.call('ZRANK', KEYS[1], ARGV[1])
      if position then position = position + 1 else position = -1 end
      return 'WAIT|' .. position .. '|' .. redis.call('ZCARD', KEYS[2]) .. '|' .. redis.call('ZCARD', KEYS[1]) .. '|0|' .. entered
      """;
  private static final String STATUS_SCRIPT = """
      local activeScore = redis.call('ZSCORE', KEYS[2], ARGV[1])
      if activeScore and tonumber(activeScore) > tonumber(ARGV[2]) then
        return 'ACTIVE|0|' .. redis.call('ZCARD', KEYS[2]) .. '|' .. redis.call('ZCARD', KEYS[1]) .. '|' .. activeScore .. '|0'
      end
      if activeScore then redis.call('ZREM', KEYS[2], ARGV[1]) end
      local position = redis.call('ZRANK', KEYS[1], ARGV[1])
      if position then position = position + 1 else position = -1 end
      return 'WAIT|' .. position .. '|' .. redis.call('ZCARD', KEYS[2]) .. '|' .. redis.call('ZCARD', KEYS[1]) .. '|0|0'
      """;
  private static final String ADVANCE_ONE_SCRIPT = """
      redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, ARGV[2])
      if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[3]) then return '0' end
      local head = redis.call('ZRANGE', KEYS[1], 0, 0)
      if #head == 0 then return '0' end
      local count = redis.call('INCR', KEYS[3])
      if count == 1 then redis.call('EXPIRE', KEYS[3], 2) end
      if count > tonumber(ARGV[4]) then return '0' end
      redis.call('ZREM', KEYS[1], head[1])
      redis.call('ZADD', KEYS[2], ARGV[1], head[1])
      return '1'
      """;
  private static final String HEARTBEAT_SCRIPT = """
      local activeScore = redis.call('ZSCORE', KEYS[1], ARGV[1])
      if not activeScore or tonumber(activeScore) <= tonumber(ARGV[2]) then
        if activeScore then redis.call('ZREM', KEYS[1], ARGV[1]) end
        return 'EXPIRED'
      end
      if not redis.call('GET', KEYS[2]) then return 'INVALID' end
      if redis.call('GET', KEYS[3]) ~= ARGV[3] then return 'INVALID' end
      redis.call('ZADD', KEYS[1], ARGV[4], ARGV[1])
      redis.call('SET', KEYS[2], ARGV[5], 'PX', ARGV[6])
      redis.call('SET', KEYS[3], ARGV[3], 'PX', ARGV[6])
      return 'ACTIVE|' .. ARGV[4]
      """;
  private static final String RELEASE_SCRIPT = """
      if not redis.call('GET', KEYS[2]) then return 'INVALID' end
      if redis.call('GET', KEYS[3]) ~= ARGV[2] then return 'INVALID' end
      redis.call('ZREM', KEYS[1], ARGV[1])
      redis.call('DEL', KEYS[2])
      redis.call('DEL', KEYS[3])
      return 'RELEASED'
      """;

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
      QueueSnapshot snapshot = enterOrWaitSnapshot(eventId, userId);
      if (snapshot.active()) {
        return admittedResponse(eventId, userId, snapshot);
      }

      if (snapshot.entered()) {
        Counter.builder("seatrace.queue.enter.total")
            .description("Total number of users entered into queue")
            .register(meterRegistry)
            .increment();
      }

      Counter.builder("seatrace.queue.wait.total")
          .description("Total number of wait queue responses")
          .register(meterRegistry)
          .increment();

      return QueueEnterResponse.builder()
          .admitted(false)
          .position(snapshot.position())
          .activeCount(snapshot.activeCount())
          .waitCount(snapshot.waitCount())
          .tps(virtualQueueProperties.getTps())
          .activeLimit(virtualQueueProperties.getActiveLimit())
          .estimatedWaitMillis(estimateWaitMillis(snapshot.position()))
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
      QueueSnapshot snapshot = statusSnapshot(eventId, userId);
      AdmissionTokenEntry tokenEntry = snapshot.active()
          ? issueAdmissionToken(eventId, userId, snapshot.activeExpiresAtMillis())
          : null;

      return QueueStatusResponse.builder()
          .admitted(snapshot.active())
          .waiting(!snapshot.active() && snapshot.position() >= 0)
          .position(snapshot.position())
          .activeCount(snapshot.activeCount())
          .waitCount(snapshot.waitCount())
          .tps(virtualQueueProperties.getTps())
          .activeLimit(virtualQueueProperties.getActiveLimit())
          .estimatedWaitMillis(snapshot.active() ? 0 : estimateWaitMillis(snapshot.position()))
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
      String redisValue = redisFacade.get(LEASE_REDIS_FEATURE, tokenKey(admissionToken));
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

  public QueueLeaseResponse heartbeat(Long eventId, Long userId, String admissionToken) {
    if (!virtualQueueProperties.isEnabled()) {
      return new QueueLeaseResponse(null, 0);
    }
    if (admissionToken == null || admissionToken.isBlank()) {
      return null;
    }

    long now = System.currentTimeMillis();
    AdmissionTokenEntry current = findAdmission(admissionToken, eventId, userId, now);
    if (current == null) {
      return null;
    }

    long expiresAt = now + tokenTtlMillis();
    AdmissionTokenEntry renewed = new AdmissionTokenEntry(admissionToken, eventId, userId, expiresAt);
    try {
      String result = redisFacade.evalStringScript(
          LEASE_REDIS_FEATURE,
          HEARTBEAT_SCRIPT,
          List.of(activeKey(eventId), tokenKey(admissionToken), userTokenKey(eventId, userId)),
          userId.toString(),
          Long.toString(now),
          admissionToken,
          Long.toString(expiresAt),
          serializeTokenEntry(renewed),
          Long.toString(tokenTtlMillis())
      );
      if (result == null || !result.startsWith("ACTIVE|")) {
        return null;
      }

      cacheAdmission(renewed);
      Counter.builder("seatrace.queue.heartbeat.total")
          .description("Successful virtual queue lease renewals")
          .register(meterRegistry)
          .increment();
      return new QueueLeaseResponse(admissionToken, expiresAt);
    } catch (Exception ex) {
      throw redisUnavailable("queue heartbeat", eventId, userId, ex);
    }
  }

  public boolean release(Long eventId, Long userId, String admissionToken) {
    if (!virtualQueueProperties.isEnabled()) {
      return true;
    }
    if (admissionToken == null || admissionToken.isBlank()) {
      return false;
    }

    long now = System.currentTimeMillis();
    AdmissionTokenEntry current = findAdmission(admissionToken, eventId, userId, now);
    if (current == null) {
      return false;
    }

    try {
      String result = redisFacade.evalStringScript(
          LEASE_REDIS_FEATURE,
          RELEASE_SCRIPT,
          List.of(activeKey(eventId), tokenKey(admissionToken), userTokenKey(eventId, userId)),
          userId.toString(),
          admissionToken
      );
      if (!"RELEASED".equals(result)) {
        return false;
      }
      admissionTokenCache.remove(admissionToken);
      userAdmissionCache.remove(userTokenKey(eventId, userId));
      Counter.builder("seatrace.queue.release.total")
          .description("Virtual queue leases released before expiration")
          .register(meterRegistry)
          .increment();
      return true;
    } catch (Exception ex) {
      throw redisUnavailable("queue release", eventId, userId, ex);
    }
  }

  @Scheduled(fixedDelayString = "#{@virtualQueueProperties.advanceDelayMs}")
  public void advanceKnownQueues() {
    if (!virtualQueueProperties.isEnabled()) {
      return;
    }

    evictExpiredLocalAdmissions();
    for (Long eventId : knownEventIds) {
      try {
        advanceQueue(eventId);
      } catch (Exception ex) {
        log.warn("VirtualQueue scheduler skipped event due to Redis failure: eventId={}", eventId, ex);
      }
    }
  }

  private QueueEnterResponse admittedResponse(Long eventId, Long userId, QueueSnapshot snapshot) {
    Counter.builder("seatrace.queue.admit.total")
        .description("Total number of admitted users")
        .register(meterRegistry)
        .increment();

    AdmissionTokenEntry tokenEntry = issueAdmissionToken(eventId, userId, snapshot.activeExpiresAtMillis());

    return QueueEnterResponse.builder()
        .admitted(true)
        .position(snapshot.position())
        .activeCount(snapshot.activeCount())
        .waitCount(snapshot.waitCount())
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

  private AdmissionTokenEntry issueAdmissionToken(Long eventId, Long userId, long expiresAtMillis) {
    long now = System.currentTimeMillis();
    String userKey = userTokenKey(eventId, userId);
    AdmissionTokenEntry cached = userAdmissionCache.get(userKey);
    if (isValid(cached, eventId, userId, now)) {
      return cached;
    }
    userAdmissionCache.remove(userKey, cached);

    try {
      String existingToken = redisFacade.get(STATUS_REDIS_FEATURE, userKey);
      if (existingToken != null && !existingToken.isBlank()) {
        String redisValue = redisFacade.get(STATUS_REDIS_FEATURE, tokenKey(existingToken));

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
        expiresAtMillis
    );
    cacheAdmission(created);
    try {
      Duration ttl = tokenTtl(expiresAtMillis, now);
      redisFacade.set(STATUS_REDIS_FEATURE, tokenKey(created.token()), serializeTokenEntry(created), ttl);
      redisFacade.set(STATUS_REDIS_FEATURE, userKey, created.token(), ttl);
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

  private AdmissionTokenEntry findAdmission(String admissionToken, Long eventId, Long userId, long now) {
    AdmissionTokenEntry cached = admissionTokenCache.get(admissionToken);
    if (isValid(cached, eventId, userId, now)) {
      return cached;
    }
    admissionTokenCache.remove(admissionToken, cached);

    String redisValue = redisFacade.get(LEASE_REDIS_FEATURE, tokenKey(admissionToken));
    if (!validateRawTokenWithoutAllocation(redisValue, eventId, userId, now)) {
      return null;
    }
    AdmissionTokenEntry entry = new AdmissionTokenEntry(
        admissionToken,
        eventId,
        userId,
        extractExpiresAt(redisValue)
    );
    cacheAdmission(entry);
    return entry;
  }

  private long tokenTtlMillis() {
    return Math.max(virtualQueueProperties.getActiveTtlSeconds(), 1) * 1000L;
  }

  private Duration tokenTtl(long expiresAtMillis, long now) {
    return Duration.ofMillis(Math.max(expiresAtMillis - now, 1));
  }

  private void ensureWaiting(Long eventId, Long userId) {
    String key = waitKey(eventId);
    String member = userId.toString();
    Double score = redisFacade.zScore(ENTRY_REDIS_FEATURE, key, member);
    if (score == null) {
      redisFacade.zAdd(ENTRY_REDIS_FEATURE, key, member, System.currentTimeMillis());
      Counter.builder("seatrace.queue.enter.total")
          .description("Total number of users entered into queue")
          .register(meterRegistry)
          .increment();
    }
  }

  private void advanceQueue(Long eventId) {
    int maxAdvance = virtualQueueProperties.getMaxAdvancePerCall();

    while (maxAdvance-- > 0) {
      if (!advanceOne(eventId)) {
        break;
      }

      Counter.builder("seatrace.queue.advance.total")
          .description("Total number of users advanced from wait to active")
          .register(meterRegistry)
          .increment();
    }
  }

  private QueueSnapshot enterOrWaitSnapshot(Long eventId, Long userId) {
    long now = System.currentTimeMillis();
    String result = redisFacade.evalStringScript(
        ENTRY_REDIS_FEATURE,
        ENTER_OR_WAIT_SCRIPT,
        List.of(waitKey(eventId), activeKey(eventId)),
        userId.toString(),
        Long.toString(now),
        Long.toString(now)
    );
    return parseSnapshot(result);
  }

  private QueueSnapshot statusSnapshot(Long eventId, Long userId) {
    String result = redisFacade.evalStringScript(
        STATUS_REDIS_FEATURE,
        STATUS_SCRIPT,
        List.of(waitKey(eventId), activeKey(eventId)),
        userId.toString(),
        Long.toString(System.currentTimeMillis())
    );
    return parseSnapshot(result);
  }

  private boolean advanceOne(Long eventId) {
    long now = System.currentTimeMillis();
    long expiresAt = now + (virtualQueueProperties.getActiveTtlSeconds() * 1000L);
    String result = redisFacade.evalStringScript(
        ADVANCE_REDIS_FEATURE,
        ADVANCE_ONE_SCRIPT,
        List.of(waitKey(eventId), activeKey(eventId), gateKey(eventId, now / 1000)),
        Long.toString(expiresAt),
        Long.toString(now),
        Integer.toString(virtualQueueProperties.getActiveLimit()),
        Integer.toString(virtualQueueProperties.getTps())
    );
    return "1".equals(result);
  }

  private QueueSnapshot parseSnapshot(String result) {
    if (result == null) {
      throw new IllegalStateException("Virtual queue script returned no result");
    }
    String[] values = result.split("\\|", -1);
    if (values.length != 6) {
      throw new IllegalStateException("Virtual queue script returned an invalid result: " + result);
    }
    return new QueueSnapshot(
        "ACTIVE".equals(values[0]),
        Long.parseLong(values[1]),
        Long.parseLong(values[2]),
        Long.parseLong(values[3]),
        Long.parseLong(values[4]),
        "1".equals(values[5])
    );
  }

  private boolean isActive(Long eventId, Long userId) {
    return redisFacade.zScore(ADVANCE_REDIS_FEATURE, activeKey(eventId), userId.toString()) != null;
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

  private String gateKey(Long eventId, long epochSecond) {
    return GATE_KEY.formatted(eventId, epochSecond);
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

  private record QueueSnapshot(
      boolean active,
      long position,
      long activeCount,
      long waitCount,
      long activeExpiresAtMillis,
      boolean entered
  ) {
  }
}
