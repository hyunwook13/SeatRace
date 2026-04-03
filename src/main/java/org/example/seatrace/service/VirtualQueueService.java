package org.example.seatrace.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.config.VirtualQueueProperties;
import org.example.seatrace.dto.queue.QueueEnterResponse;
import org.example.seatrace.dto.queue.QueueStatusResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualQueueService {

  private static final String WAIT_KEY = "seat-race:queue:wait:%d";
  private static final String ACTIVE_KEY = "seat-race:queue:active:%d";
  private static final String GATE_KEY = "seat-race:queue:gate:%d:%d";

  private final StringRedisTemplate stringRedisTemplate;
  private final VirtualQueueProperties virtualQueueProperties;
  private final MeterRegistry meterRegistry;

  public QueueEnterResponse enterOrWait(Long eventId, Long userId) {
    if (!virtualQueueProperties.isEnabled()) {
      return admittedResponse(eventId, 0);
    }

    cleanupExpiredActive(eventId);
    advanceQueue(eventId);

    if (isActive(eventId, userId)) {
      return admittedResponse(eventId, 0);
    }

    ensureWaiting(eventId, userId);
    advanceQueue(eventId);

    long position = getPosition(eventId, userId);
    boolean admitted = isActive(eventId, userId);
    if (admitted) {
      return admittedResponse(eventId, 0);
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
        .build();
  }

  public QueueStatusResponse status(Long eventId, Long userId) {
    if (!virtualQueueProperties.isEnabled()) {
      return QueueStatusResponse.builder()
          .admitted(true)
          .waiting(false)
          .position(0)
          .activeCount(getActiveCount(eventId))
          .waitCount(getWaitCount(eventId))
          .tps(virtualQueueProperties.getTps())
          .activeLimit(virtualQueueProperties.getActiveLimit())
          .estimatedWaitMillis(0)
          .build();
    }

    cleanupExpiredActive(eventId);
    advanceQueue(eventId);

    boolean admitted = isActive(eventId, userId);
    long position = admitted ? 0 : getPosition(eventId, userId);
    boolean waiting = !admitted && position >= 0;

    return QueueStatusResponse.builder()
        .admitted(admitted)
        .waiting(waiting)
        .position(position)
        .activeCount(getActiveCount(eventId))
        .waitCount(getWaitCount(eventId))
        .tps(virtualQueueProperties.getTps())
        .activeLimit(virtualQueueProperties.getActiveLimit())
        .estimatedWaitMillis(admitted ? 0 : estimateWaitMillis(position))
        .build();
  }

  private QueueEnterResponse admittedResponse(Long eventId, long position) {
    Counter.builder("seatrace.queue.admit.total")
        .description("Total number of admitted users")
        .register(meterRegistry)
        .increment();

    return QueueEnterResponse.builder()
        .admitted(true)
        .position(position)
        .activeCount(getActiveCount(eventId))
        .waitCount(getWaitCount(eventId))
        .tps(virtualQueueProperties.getTps())
        .activeLimit(virtualQueueProperties.getActiveLimit())
        .estimatedWaitMillis(0)
        .build();
  }

  private void ensureWaiting(Long eventId, Long userId) {
    String key = waitKey(eventId);
    String member = userId.toString();
    Double score = stringRedisTemplate.opsForZSet().score(key, member);
    if (score == null) {
      stringRedisTemplate.opsForZSet().add(key, member, System.currentTimeMillis());
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

      Set<String> head = stringRedisTemplate.opsForZSet().range(waitKey(eventId), 0, 0);
      if (head == null || head.isEmpty()) {
        break;
      }

      if (!gateAllows(eventId)) {
        break;
      }

      String member = head.iterator().next();
      stringRedisTemplate.opsForZSet().remove(waitKey(eventId), member);
      stringRedisTemplate.opsForZSet().add(activeKey(eventId), member, System.currentTimeMillis());

      Counter.builder("seatrace.queue.advance.total")
          .description("Total number of users advanced from wait to active")
          .register(meterRegistry)
          .increment();
    }
  }

  private boolean gateAllows(Long eventId) {
    long epochSecond = System.currentTimeMillis() / 1000;
    String key = GATE_KEY.formatted(eventId, epochSecond);
    Long count = stringRedisTemplate.opsForValue().increment(key);
    if (count != null && count == 1L) {
      stringRedisTemplate.expire(key, Duration.ofSeconds(2));
    }
    return count != null && count <= virtualQueueProperties.getTps();
  }

  private void cleanupExpiredActive(Long eventId) {
    long cutoff = System.currentTimeMillis() - (virtualQueueProperties.getActiveTtlSeconds() * 1000L);
    stringRedisTemplate.opsForZSet().removeRangeByScore(activeKey(eventId), 0, cutoff);
  }

  private boolean isActive(Long eventId, Long userId) {
    return stringRedisTemplate.opsForZSet().score(activeKey(eventId), userId.toString()) != null;
  }

  private long getPosition(Long eventId, Long userId) {
    Long rank = stringRedisTemplate.opsForZSet().rank(waitKey(eventId), userId.toString());
    return rank == null ? -1 : rank + 1;
  }

  private long getActiveCount(Long eventId) {
    Long count = stringRedisTemplate.opsForZSet().size(activeKey(eventId));
    return count == null ? 0 : count;
  }

  private long getWaitCount(Long eventId) {
    Long count = stringRedisTemplate.opsForZSet().size(waitKey(eventId));
    return count == null ? 0 : count;
  }

  private long estimateWaitMillis(long position) {
    int tps = Math.max(virtualQueueProperties.getTps(), 1);
    if (position <= 0) {
      return 0;
    }
    return (position * 1000L) / tps;
  }

  private String waitKey(Long eventId) {
    return WAIT_KEY.formatted(eventId);
  }

  private String activeKey(Long eventId) {
    return ACTIVE_KEY.formatted(eventId);
  }
}
