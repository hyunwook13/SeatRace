package org.example.seatrace.service;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.config.ReservationHoldProperties;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationHoldRedisService {

  private static final String RESERVATION_HOLD_KEY = "seat-race:reservation:hold:%d";
  private static final String EVENT_SEAT_HOLD_KEY = "seat-race:event-seat:hold:%d";

  private final RedisFacade redisFacade;
  private final ReservationHoldProperties reservationHoldProperties;

  public Duration holdTtl() {
    return Duration.ofSeconds(reservationHoldProperties.getTtlSeconds());
  }

  public void registerHold(Long reservationId, List<Long> eventSeatIds) {
    Duration ttl = holdTtl();
    try {
      redisFacade.set(reservationHoldKey(reservationId), reservationId.toString(), ttl);

      for (Long eventSeatId : eventSeatIds) {
        redisFacade.set(eventSeatHoldKey(eventSeatId), reservationId.toString(), ttl);
      }
    } catch (Exception ex) {
      // afterCommit에서 호출되는 경우가 많아서 예외를 밖으로 던지지 않는다.
      log.warn("Redis hold 등록 실패: reservationId={}, eventSeatIds={}", reservationId, eventSeatIds, ex);
    }
  }

  public boolean hasAnyEventSeatHold(List<Long> eventSeatIds) {
    try {
      for (Long eventSeatId : eventSeatIds) {
        if (Boolean.TRUE.equals(redisFacade.hasKey(eventSeatHoldKey(eventSeatId)))) {
          return true;
        }
      }
      return false;
    } catch (Exception ex) {
      // Redis는 옵션(캐시/fast-fail) 용도: 장애 시 fast-fail을 비활성화하고 DB 로직으로 진행한다.
      log.warn("Redis hasAnyEventSeatHold failed. fallback=false (skip fast-fail). eventSeatIds={}",
          eventSeatIds, ex);
      return false;
    }
  }

  public boolean isReservationHoldAlive(Long reservationId) {
    try {
      return Boolean.TRUE.equals(redisFacade.hasKey(reservationHoldKey(reservationId)));
    } catch (Exception ex) {
      // Redis 장애 시 false로 판단하면 잘못된 만료/정리가 발생할 수 있어 보수적으로 'alive'로 취급한다.
      log.warn("Redis isReservationHoldAlive failed. fallback=true (skip cleanup). reservationId={}",
          reservationId, ex);
      return true;
    }
  }

  public void clearHold(Long reservationId, List<Long> eventSeatIds) {
    try {
      redisFacade.delete(reservationHoldKey(reservationId));

      if (!eventSeatIds.isEmpty()) {
        redisFacade.delete(eventSeatIds.stream().map(this::eventSeatHoldKey).toList());
      }
    } catch (Exception ex) {
      // afterCommit에서 호출되는 경우가 많아서 예외를 밖으로 던지지 않는다.
      log.warn("Redis hold 삭제 실패: reservationId={}, eventSeatIds={}", reservationId, eventSeatIds, ex);
    }
  }

  private String reservationHoldKey(Long reservationId) {
    return RESERVATION_HOLD_KEY.formatted(reservationId);
  }

  private String eventSeatHoldKey(Long eventSeatId) {
    return EVENT_SEAT_HOLD_KEY.formatted(eventSeatId);
  }
}
