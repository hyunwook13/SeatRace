package org.example.seatrace.dto.reservation;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class HoldSeatRequest {
  @NotEmpty
  @NotNull
  private List<Long> seatIds;
}