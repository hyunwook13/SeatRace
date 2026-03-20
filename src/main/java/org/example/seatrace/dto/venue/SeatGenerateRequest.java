package org.example.seatrace.dto.venue;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SeatGenerateRequest {

  @Min(1)
  private int count;

  @NotBlank
  private String grade = "STANDARD";

  @NotBlank
  private String section = "AUTO";
}
