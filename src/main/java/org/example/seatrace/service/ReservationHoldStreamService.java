package org.example.seatrace.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationHoldStreamService {

  private static final String STREAM_KEY = "seat-race:reservation:hold:expire-stream";
  private static final String STREAM_LAST_ID_KEY = "seat-race:reservation:hold:expire-stream:last-id";
  private static final String MAX_SEQUENCE = "18446744073709551615";
  private static final int MAX_ADD_RETRY = 5;

  private final RedisFacade redisFacade;
  private final MeterRegistry meterRegistry;

  public void enqueueHold(Long reservationId, LocalDateTime expiresAt) {
    if (reservationId == null || expiresAt == null) {
      return;
    }

    long expiresAtMillis = expiresAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    Map<String, String> body = Map.of(
        "reservationId", reservationId.toString(),
        "expiresAtMillis", String.valueOf(expiresAtMillis)
    );
    boolean added = false;
    long seq = reservationId;
    Exception lastException = null;

    for (int attempt = 0; attempt < MAX_ADD_RETRY; attempt++) {
      try {
        redisFacade.xAdd(STREAM_KEY, expiresAtMillis + "-" + seq, body);
        added = true;
        Counter.builder("seatrace.hold.stream.enqueued.total")
            .description("Total number of hold expiration entries enqueued to Redis Stream")
            .register(meterRegistry)
            .increment();
        break;
      } catch (InvalidDataAccessApiUsageException ex) {
        seq++;
        lastException = ex;
      } catch (Exception ex) {
        lastException = ex;
        break;
      }
    }

    if (!added) {
      Counter.builder("seatrace.hold.stream.enqueue.fail.total")
          .description("Failed to enqueue hold expiration entry to Redis Stream")
          .register(meterRegistry)
          .increment();
      log.error("홀드 만료 스트림 enqueue 실패: reservationId={}, expiresAtMillis={}",
          reservationId, expiresAtMillis, lastException);
    }
  }

  public HoldExpireBatch readDueBatch(int maxCount) {
    if (maxCount <= 0) {
      return HoldExpireBatch.empty();
    }

    String lastId;
    List<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>> records;
    try {
      lastId = getLastProcessedId();
      long nowMillis = System.currentTimeMillis();
      String maxId = nowMillis + "-" + MAX_SEQUENCE;

      Range<String> range = Range.of(Range.Bound.exclusive(lastId), Range.Bound.inclusive(maxId));
      records = redisFacade.xRange(STREAM_KEY, range, maxCount);
    } catch (Exception ex) {
      log.warn("홀드 만료 스트림 read 실패", ex);
      return HoldExpireBatch.empty();
    }

    if (records == null || records.isEmpty()) {
      Counter.builder("seatrace.hold.stream.empty.total")
          .description("Number of times no due hold entries were found in the stream")
          .register(meterRegistry)
          .increment();
      return HoldExpireBatch.empty();
    }

    List<Long> reservationIds = new ArrayList<>(records.size());
    List<RecordId> recordIds = new ArrayList<>(records.size());
    for (org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object> record : records) {
      recordIds.add(record.getId());
      Object rawReservationIdValue = record.getValue().get("reservationId");
      String rawReservationId = rawReservationIdValue == null ? null : rawReservationIdValue.toString();
      if (rawReservationId != null) {
        try {
          reservationIds.add(Long.parseLong(rawReservationId));
        } catch (NumberFormatException ex) {
          log.warn("홀드 만료 스트림 reservationId 파싱 실패: value={}", rawReservationId);
        }
      }
    }

    Counter.builder("seatrace.hold.stream.due.read.total")
        .description("Total number of due hold entries read from stream")
        .register(meterRegistry)
        .increment(records.size());

    String newLastId = records.get(records.size() - 1).getId().getValue();
    return new HoldExpireBatch(reservationIds, recordIds, newLastId);
  }

  public void markProcessed(HoldExpireBatch batch) {
    if (batch == null || batch.recordIds().isEmpty()) {
      return;
    }

    RecordId[] ids = batch.recordIds().toArray(new RecordId[0]);
    try {
      redisFacade.xDel(STREAM_KEY, ids);
      redisFacade.set(STREAM_LAST_ID_KEY, batch.lastId());
    } catch (Exception ex) {
      log.warn("홀드 만료 스트림 markProcessed 실패: lastId={}", batch.lastId(), ex);
      return;
    }

    Counter.builder("seatrace.hold.stream.deleted.total")
        .description("Total number of processed hold entries deleted from stream")
        .register(meterRegistry)
        .increment(batch.recordIds().size());
  }

  private String getLastProcessedId() {
    try {
      String lastId = redisFacade.get(STREAM_LAST_ID_KEY);
      return Objects.requireNonNullElse(lastId, "0-0");
    } catch (Exception ex) {
      return "0-0";
    }
  }

  public record HoldExpireBatch(List<Long> reservationIds, List<RecordId> recordIds, String lastId) {
    public static HoldExpireBatch empty() {
      return new HoldExpireBatch(List.of(), List.of(), "0-0");
    }

    public boolean isEmpty() {
      return recordIds.isEmpty();
    }
  }
}
