package org.example.seatrace.dto.seat;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class EventSeatBulkCreateResponse {

  private final Long eventId;
  private final Long seatSectionId;
  private final int createdCount;
  private final List<EventSeatSummary> createdSeats;
}
