package org.example.seatrace.dto.seat;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SeatSectionCreateRequest {

  @NotBlank
  private String name;
}
