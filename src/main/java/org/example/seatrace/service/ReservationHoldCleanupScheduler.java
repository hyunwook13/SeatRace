package org.example.seatrace.service;

import java.util.ArrayList;
import java.util.List;
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
  private final ReservationHoldStreamService reservationHoldStreamService;

  @Scheduled(fixedDelayString = "#{@reservationHoldProperties.cleanupDelayMs}")
  public void cleanupExpiredHolds() {
    ReservationHoldStreamService.HoldExpireBatch batch =
        reservationHoldStreamService.readDueBatch(reservationHoldProperties.getStreamBatchSize());
    if (batch.isEmpty()) {
      return;
    }
    if (batch.reservationIds().isEmpty()) {
      reservationHoldStreamService.markProcessed(batch);
      return;
    }

    int expiredCount = 0;
    for (List<Long> chunk : partition(batch.reservationIds(), reservationHoldProperties.getChunkSize())) {
      expiredCount += attemptExpireChunk(chunk);
    }

    reservationHoldStreamService.markProcessed(batch);

    if (expiredCount > 0) {
      log.info("Redis TTL 만료 HOLD 정리 완료: expiredCount={}, ttlSeconds={}",
          expiredCount,
          reservationHoldProperties.getTtlSeconds());
    }
  }

  private int attemptExpireChunk(List<Long> reservationIds) {
    try {
      return reservationService.expireReservationHoldChunk(reservationIds, "scheduler");
    } catch (Exception ex) {
      log.error("예약 홀드 정리 실패: reservationIds={}, message={}",
          reservationIds, ex.getMessage());
      return 0;
    }
  }

  private List<List<Long>> partition(List<Long> values, int chunkSize) {
    if (chunkSize <= 0) {
      throw new IllegalArgumentException("chunkSize must be greater than 0");
    }
    List<List<Long>> chunks = new ArrayList<>();
    for (int i = 0; i < values.size(); i += chunkSize) {
      int end = Math.min(values.size(), i + chunkSize);
      chunks.add(values.subList(i, end));
    }
    return chunks;
  }
}
