package org.example.seatrace.dto.seat;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BulkSeatCreateResponse {

  private final Long venueId;
  private final int requestedCount;
  private final int createdCount;
  private final int skippedCount;
  private final List<SeatSummary> createdSeats;
}
