package org.example.seatrace.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.config.ReservationHoldProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationHoldCleanupScheduler {

  private final ReservationService reservationService;
  private final ReservationHoldProperties reservationHoldProperties;

  @Scheduled(fixedDelayString = "#{@reservationHoldProperties.cleanupDelayMs}")
  public void cleanupExpiredHolds() {
    int expiredCount = reservationService.expireMissingRedisHolds();

    if (expiredCount > 0) {
      log.info("Redis TTL 만료 HOLD 정리 완료: expiredCount={}, ttlSeconds={}",
          expiredCount,
          reservationHoldProperties.getTtlSeconds());
    }
  }
}
