package org.example.seatrace.dto;

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
  private final String grade;

  public static SeatSummary from(Seat seat) {
    return new SeatSummary(seat.getId(), seat.getSection(), seat.getRowNo(), seat.getSeatNo(),
        seat.getGrade());
  }
}
