package org.example.seatrace.dto.seat;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.entity.EventSeatStatus;

@Getter
@RequiredArgsConstructor
public class EventSeatListResponse {

  private final List<EventSeatSummary> seats;
  private final Map<EventSeatStatus, Long> counts;

  public static EventSeatListResponse from(List<EventSeatSummary> seats) {
    EnumMap<EventSeatStatus, Long> counts = new EnumMap<>(EventSeatStatus.class);
    for (EventSeatStatus status : EventSeatStatus.values()) {
      counts.put(status, 0L);
    }

    seats.forEach(seat -> counts.compute(seat.getStatus(), (k, v) -> v == null ? 1L : v + 1));

    return new EventSeatListResponse(seats, counts);
  }
}
