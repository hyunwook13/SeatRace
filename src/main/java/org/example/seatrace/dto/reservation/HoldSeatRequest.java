package org.example.seatrace.dto.reservation;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class HoldSeatRequest {
  @NotEmpty
  private List<Long> seatIds;
}