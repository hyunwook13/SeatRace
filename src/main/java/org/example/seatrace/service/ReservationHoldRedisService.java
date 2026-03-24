package org.example.seatrace.service;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.config.ReservationHoldProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReservationHoldRedisService {

  private static final String RESERVATION_HOLD_KEY = "seat-race:reservation:hold:%d";
  private static final String EVENT_SEAT_HOLD_KEY = "seat-race:event-seat:hold:%d";

  private final StringRedisTemplate stringRedisTemplate;
  private final ReservationHoldProperties reservationHoldProperties;

  public Duration holdTtl() {
    return Duration.ofSeconds(reservationHoldProperties.getTtlSeconds());
  }

  public void registerHold(Long reservationId, List<Long> eventSeatIds) {
    Duration ttl = holdTtl();
    stringRedisTemplate.opsForValue()
        .set(reservationHoldKey(reservationId), reservationId.toString(), ttl);

    for (Long eventSeatId : eventSeatIds) {
      stringRedisTemplate.opsForValue()
          .set(eventSeatHoldKey(eventSeatId), reservationId.toString(), ttl);
    }
  }

  public boolean isReservationHoldAlive(Long reservationId) {
    return Boolean.TRUE.equals(stringRedisTemplate.hasKey(reservationHoldKey(reservationId)));
  }

  public void clearHold(Long reservationId, List<Long> eventSeatIds) {
    stringRedisTemplate.delete(reservationHoldKey(reservationId));

    if (!eventSeatIds.isEmpty()) {
      stringRedisTemplate.delete(eventSeatIds.stream()
          .map(this::eventSeatHoldKey)
          .toList());
    }
  }

  private String reservationHoldKey(Long reservationId) {
    return RESERVATION_HOLD_KEY.formatted(reservationId);
  }

  private String eventSeatHoldKey(Long eventSeatId) {
    return EVENT_SEAT_HOLD_KEY.formatted(eventSeatId);
  }
}
