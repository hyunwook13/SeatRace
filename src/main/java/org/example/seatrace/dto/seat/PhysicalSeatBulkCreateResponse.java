package org.example.seatrace.dto.seat;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PhysicalSeatBulkCreateResponse {

  private final Long seatSectionId;
  private final int requestedCount;
  private final int createdCount;
  private final int skippedCount;
  private final List<SeatSummary> createdSeats;
}
