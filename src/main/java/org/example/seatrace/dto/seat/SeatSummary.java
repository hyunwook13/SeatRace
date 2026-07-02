package org.example.seatrace.dto.seat;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.entity.Seat;

@Getter
@RequiredArgsConstructor
public class SeatSummary {

  private final Long id;
  private final String section;
  private final String rowNo;
  private final String seatNo;

  public static SeatSummary from(Seat seat) {
    return new SeatSummary(seat.getId(), seat.getSeatSection().getName(), seat.getSeatRow(),
        String.valueOf(seat.getSeatNumber()));
  }
}
