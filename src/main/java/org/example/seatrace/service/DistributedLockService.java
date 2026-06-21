package org.example.seatrace.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.config.ReservationLockProperties;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * Redis 기반 분산락( Redisson ) 래퍼.
 *
 * - 좌석 선점(hold) 시 DB 락 대신 per-seat 락을 잡아 DB 커넥션/락 경합을 줄인다.
 * - 멀티 좌석 요청은 key 정렬 후 순서대로 락을 잡아 교착을 방지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {

  private static final String SEAT_LOCK_KEY = "lock:seat:%d:%d"; // eventId:seatId
  private static final String RESERVATION_LOCK_KEY = "lock:reservation:%d";

  private final RedissonClient redissonClient;
  private final ReservationLockProperties reservationLockProperties;
  private final MeterRegistry meterRegistry;

  public LockHandle tryLockSeats(long eventId, List<Long> seatIds) {
    if (!reservationLockProperties.isEnabled()) {
      return LockHandle.noop();
    }
    if (seatIds == null || seatIds.isEmpty()) {
      return LockHandle.noop();
    }

    List<Long> sorted = new ArrayList<>(seatIds);
    Collections.sort(sorted);

    long waitMs = reservationLockProperties.getWaitMs();
    long leaseMs = reservationLockProperties.getLeaseMs();

    List<RLock> acquired = new ArrayList<>(sorted.size());
    try {
      for (Long seatId : sorted) {
        if (seatId == null) {
          continue;
        }
        String key = SEAT_LOCK_KEY.formatted(eventId, seatId);
        RLock lock = redissonClient.getLock(key);
        boolean ok = lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS);
        if (!ok) {
          unlockQuietly(acquired);
          counter("seatrace.lock.seat.fail.total").increment();
          return null;
        }
        acquired.add(lock);
      }
      counter("seatrace.lock.seat.success.total").increment();
      return new LockHandle(acquired);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      unlockQuietly(acquired);
      counter("seatrace.lock.seat.fail.total").increment();
      return null;
    } catch (Exception ex) {
      unlockQuietly(acquired);
      counter("seatrace.lock.seat.fail.total").increment();
      log.warn("Seat distributed lock failed: eventId={}, seatIds={}", eventId, seatIds, ex);
      return null;
    }
  }

  public LockHandle tryLockReservation(long reservationId) {
    if (!reservationLockProperties.isEnabled()) {
      return LockHandle.noop();
    }
    long waitMs = reservationLockProperties.getWaitMs();
    long leaseMs = reservationLockProperties.getLeaseMs();

    RLock lock = redissonClient.getLock(RESERVATION_LOCK_KEY.formatted(reservationId));
    try {
      boolean ok = lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS);
      if (!ok) {
        counter("seatrace.lock.reservation.fail.total").increment();
        return null;
      }
      counter("seatrace.lock.reservation.success.total").increment();
      return new LockHandle(List.of(lock));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      counter("seatrace.lock.reservation.fail.total").increment();
      return null;
    } catch (Exception ex) {
      counter("seatrace.lock.reservation.fail.total").increment();
      log.warn("Reservation distributed lock failed: reservationId={}", reservationId, ex);
      return null;
    }
  }

  private Counter counter(String name) {
    return Counter.builder(name).register(meterRegistry);
  }

  private static void unlockQuietly(List<RLock> locks) {
    for (int i = locks.size() - 1; i >= 0; i--) {
      RLock lock = locks.get(i);
      try {
        if (lock.isHeldByCurrentThread()) {
          lock.unlock();
        }
      } catch (Exception ignored) {
      }
    }
  }

  public record LockHandle(List<RLock> locks) {
    public static LockHandle noop() {
      return new LockHandle(List.of());
    }

    public void unlockQuietly() {
      DistributedLockService.unlockQuietly(locks);
    }
  }
}
