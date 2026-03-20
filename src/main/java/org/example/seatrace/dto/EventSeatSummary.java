package org.example.seatrace.dto;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.entity.EventSeat;
import org.example.seatrace.entity.EventSeatStatus;

@Getter
@RequiredArgsConstructor
public class EventSeatSummary {

  private final Long seatId;
  private final String section;
  private final String rowNo;
  private final String seatNo;
  private final String grade;
  private final EventSeatStatus status;
  private final LocalDateTime heldUntil;

  public static EventSeatSummary from(EventSeat seat) {
    return new EventSeatSummary(seat.getSeat().getId(), seat.getSeat().getSection(),
        seat.getSeat().getRowNo(), seat.getSeat().getSeatNo(), seat.getSeat().getGrade(),
        seat.getStatus(), seat.getHeldUntil());
  }
}
