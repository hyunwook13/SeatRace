package org.example.seatrace.dto.seat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import lombok.Getter;
import org.example.seatrace.entity.EventSeat;
import org.example.seatrace.entity.EventSeatStatus;

@Getter
public class EventSeatSummary {

  private final Long seatId;
  private final String section;
  private final String rowNo;
  private final String seatNo;
  private final String grade;
  private final Long price;
  private final EventSeatStatus status;
  private final LocalDateTime heldUntil;

  @JsonCreator
  public EventSeatSummary(
      @JsonProperty("seatId") Long seatId,
      @JsonProperty("section") String section,
      @JsonProperty("rowNo") String rowNo,
      @JsonProperty("seatNo") String seatNo,
      @JsonProperty("grade") String grade,
      @JsonProperty("price") Long price,
      @JsonProperty("status") EventSeatStatus status,
      @JsonProperty("heldUntil") LocalDateTime heldUntil
  ) {
    this.seatId = seatId;
    this.section = section;
    this.rowNo = rowNo;
    this.seatNo = seatNo;
    this.grade = grade;
    this.price = price;
    this.status = status;
    this.heldUntil = heldUntil;
  }

  public static EventSeatSummary from(EventSeat seat) {
    Long price = seat.getPrice();
    String grade = seat.getGrade() != null ? seat.getGrade().name() : null;

    return new EventSeatSummary(seat.getSeat().getId(),
        seat.getSeat().getSeatSection().getName(),
        seat.getSeat().getSeatRow(),
        String.valueOf(seat.getSeat().getSeatNumber()), grade, price, seat.getStatus(),
        seat.getHeldUntil());
  }
}
