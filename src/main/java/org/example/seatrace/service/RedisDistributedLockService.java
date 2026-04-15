package org.example.seatrace.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.config.DistributedLockProperties;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisDistributedLockService {

  private final RedisFacade redisFacade;
  private final DistributedLockProperties lockProperties;
  private final MeterRegistry meterRegistry;

  public LockToken acquireLocks(List<String> keys, String lockType) {
    if (!lockProperties.isEnabled() || keys.isEmpty()) {
      return LockToken.noop();
    }

    long deadline = System.currentTimeMillis() + lockProperties.getWaitTimeoutMs();
    List<String> acquired = new ArrayList<>();
    String token = UUID.randomUUID().toString();
    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      int attempts = 0;
      while (System.currentTimeMillis() < deadline && attempts++ < lockProperties.getMaxRetry()) {
        for (String key : keys) {
          if (acquired.contains(key)) {
            continue;
          }
          Boolean ok = redisFacade.setIfAbsent(
              key,
              token,
              Duration.ofMillis(lockProperties.getLeaseMs())
          );
          if (Boolean.TRUE.equals(ok)) {
            acquired.add(key);
          } else {
            releaseLocks(acquired, token);
            acquired.clear();
            sleep(lockProperties.getRetryDelayMs());
            break;
          }
        }

        if (acquired.size() == keys.size()) {
          Counter.builder("seatrace.lock.acquire.success.total")
              .description("Total successful distributed lock acquisitions")
              .tag("type", lockType)
              .register(meterRegistry)
              .increment();
          return new LockToken(token, new ArrayList<>(acquired), lockType, false);
        }
      }

      Counter.builder("seatrace.lock.acquire.fail.total")
          .description("Total failed distributed lock acquisitions")
          .tag("type", lockType)
          .register(meterRegistry)
          .increment();
      return null;
    } finally {
      sample.stop(Timer.builder("seatrace.lock.acquire.duration")
          .description("Distributed lock acquisition duration")
          .tag("type", lockType)
          .register(meterRegistry));
    }
  }

  public void release(LockToken token) {
    if (token == null || token.noOp()) {
      return;
    }
    releaseLocks(token.keys(), token.token());
    Counter.builder("seatrace.lock.release.total")
        .description("Total distributed lock releases")
        .tag("type", token.type())
        .register(meterRegistry)
        .increment();
  }

  private void releaseLocks(List<String> keys, String token) {
    for (String key : keys) {
      try {
        redisFacade.evalUnlockScript(key, token);
      } catch (Exception ex) {
        log.warn("분산락 해제 실패: key={}", key, ex);
      }
    }
  }

  private void sleep(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  public record LockToken(String token, List<String> keys, String type, boolean noOp) {
    public static LockToken noop() {
      return new LockToken("", List.of(), "noop", true);
    }
  }
}
