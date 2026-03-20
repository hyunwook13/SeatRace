package org.example.seatrace.dto.seat;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class EventSeatStats {

  private final Long eventId;
  private final Long totalSeats;
  private final Long availableSeats;
  public static EventSeatStats emptyFor(Long eventId) {
    return new EventSeatStats(eventId, 0L, 0L);
  }
}
