package org.example.seatrace.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.entity.Event;
import org.example.seatrace.entity.EventSeat;

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
  private final BigDecimal minPrice;
  private final BigDecimal maxPrice;
  private final List<EventSeatSummary> seats;

  public static EventResponse from(Event event, List<EventSeat> seatList, BigDecimal minPrice,
      BigDecimal maxPrice) {
    return new EventResponse(
        event.getId(),
        event.getName(),
        EventStatusSummary.from(event.getStatus()),
        event.getStartAt().toString(),
        event.getEndAt().toString(),
        new VenueSummary(event.getVenue().getId(), event.getVenue().getName()),
        seatList.size(),
        minPrice,
        maxPrice,
        seatList.stream().map(EventSeatSummary::from).toList());
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
