package org.example.seatrace.dto.seat;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.seatrace.entity.SeatGrade;

@Getter
@NoArgsConstructor
public class EventSeatCreateRequest {

  @NotNull
  private SeatGrade grade;

  @NotNull
  @Min(0)
  private Long price;
}
