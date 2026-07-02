package org.example.seatrace.dto.seat;

import lombok.Builder;
import lombok.Getter;
import org.example.seatrace.entity.SeatSection;

@Getter
@Builder
public class SeatSectionResponse {

  private final Long id;
  private final Long venueId;
  private final String name;

  public static SeatSectionResponse from(SeatSection seatSection) {
    return SeatSectionResponse.builder()
        .id(seatSection.getId())
        .venueId(seatSection.getVenue().getId())
        .name(seatSection.getName())
        .build();
  }
}
