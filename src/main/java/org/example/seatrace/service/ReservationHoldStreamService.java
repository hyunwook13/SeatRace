package org.example.seatrace.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.data.redis.connection.RedisSystemException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StreamRecords;
import org.springframework.data.redis.connection.Limit;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationHoldStreamService {

  private static final String STREAM_KEY = "seat-race:reservation:hold:expire-stream";
  private static final String STREAM_LAST_ID_KEY = "seat-race:reservation:hold:expire-stream:last-id";
  private static final String MAX_SEQUENCE = "18446744073709551615";
  private static final int MAX_ADD_RETRY = 5;

  private final StringRedisTemplate stringRedisTemplate;
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

    StreamOperations<String, String, String> ops = stringRedisTemplate.opsForStream();
    boolean added = false;
    long seq = reservationId;

    for (int attempt = 0; attempt < MAX_ADD_RETRY; attempt++) {
      try {
        RecordId id = RecordId.of(expiresAtMillis + "-" + seq);
        MapRecord<String, String, String> record =
            StreamRecords.newRecord().in(STREAM_KEY).ofMap(body).withId(id);
        ops.add(record);
        added = true;
        Counter.builder("seatrace.hold.stream.enqueued.total")
            .description("Total number of hold expiration entries enqueued to Redis Stream")
            .register(meterRegistry)
            .increment();
        break;
      } catch (InvalidDataAccessApiUsageException | RedisSystemException ex) {
        seq++;
      }
    }

    if (!added) {
      Counter.builder("seatrace.hold.stream.enqueue.fail.total")
          .description("Failed to enqueue hold expiration entry to Redis Stream")
          .register(meterRegistry)
          .increment();
      log.error("홀드 만료 스트림 enqueue 실패: reservationId={}, expiresAtMillis={}",
          reservationId, expiresAtMillis);
    }
  }

  public HoldExpireBatch readDueBatch(int maxCount) {
    if (maxCount <= 0) {
      return HoldExpireBatch.empty();
    }

    String lastId = getLastProcessedId();
    long nowMillis = System.currentTimeMillis();
    String maxId = nowMillis + "-" + MAX_SEQUENCE;

    Range<String> range = Range.of(Range.Bound.exclusive(lastId), Range.Bound.inclusive(maxId));
    List<MapRecord<String, String, String>> records =
        stringRedisTemplate.opsForStream().range(STREAM_KEY, range, Limit.limit().count(maxCount));

    if (records == null || records.isEmpty()) {
      Counter.builder("seatrace.hold.stream.empty.total")
          .description("Number of times no due hold entries were found in the stream")
          .register(meterRegistry)
          .increment();
      return HoldExpireBatch.empty();
    }

    List<Long> reservationIds = new ArrayList<>(records.size());
    List<RecordId> recordIds = new ArrayList<>(records.size());
    for (MapRecord<String, String, String> record : records) {
      recordIds.add(record.getId());
      String rawReservationId = record.getValue().get("reservationId");
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
    stringRedisTemplate.opsForStream().delete(STREAM_KEY, ids);
    stringRedisTemplate.opsForValue().set(STREAM_LAST_ID_KEY, batch.lastId());

    Counter.builder("seatrace.hold.stream.deleted.total")
        .description("Total number of processed hold entries deleted from stream")
        .register(meterRegistry)
        .increment(batch.recordIds().size());
  }

  private String getLastProcessedId() {
    String lastId = stringRedisTemplate.opsForValue().get(STREAM_LAST_ID_KEY);
    return Objects.requireNonNullElse(lastId, "0-0");
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
