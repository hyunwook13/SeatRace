package org.example.seatrace.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.entity.ReservationHoldDeadletter;
import org.example.seatrace.repository.ReservationHoldDeadletterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationHoldDeadletterService {

  private final ReservationHoldDeadletterRepository repository;
  private final MeterRegistry meterRegistry;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordChunkFailure(List<Long> reservationIds, String source, Throwable ex) {
    String ids = reservationIds.stream()
        .map(String::valueOf)
        .collect(Collectors.joining(","));
    String errorType = ex == null ? null : ex.getClass().getName();
    String errorMessage = ex == null ? null : ex.getMessage();

    repository.save(new ReservationHoldDeadletter(
        ids,
        source,
        reservationIds.size(),
        errorType,
        errorMessage
    ));

    Counter.builder("seatrace_hold_stale_deadletter_total")
        .description("Stale HOLD entries that exceeded retry attempts")
        .register(meterRegistry)
        .increment();

    log.error("예약 홀드 정리 데드레터: reservationIds={}, errorType={}", ids, errorType);
  }
}
