package org.example.seatrace.dto.venue;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.EventSeatStats;
import org.example.seatrace.entity.Event;

@Getter
@RequiredArgsConstructor
public class EventResponse {

  private final Long id;
  private final String name;
  private final EventStatusSummary status;
  private final String startAt;
  private final String endAt;
  private final VenueSummary venue;
  private final int seatCount;
  private final int availableSeats;

  public static EventResponse from(Event event, EventSeatStats stats) {
    EventSeatStats normalized = stats != null ? stats : EventSeatStats.emptyFor(event.getId());
    return new EventResponse(
        event.getId(),
        event.getName(),
        EventStatusSummary.from(event.getStatus()),
        event.getStartAt().toString(),
        event.getEndAt().toString(),
        new VenueSummary(event.getVenue().getId(), event.getVenue().getName()),
        normalized.getTotalSeats().intValue(),
        normalized.getAvailableSeats().intValue());
  }

  @Getter
  @RequiredArgsConstructor
  public static class VenueSummary {
    private final Long id;
    private final String name;
  }

  @Getter
  @RequiredArgsConstructor
  public static class EventStatusSummary {
    private final String value;

    public static EventStatusSummary from(org.example.seatrace.entity.EventStatus status) {
      return new EventStatusSummary(status.name());
    }
  }
}
