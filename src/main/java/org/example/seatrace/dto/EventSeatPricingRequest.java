package org.example.seatrace.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class EventSeatPricingRequest {

  @NotNull
  private Long seatId;

  @NotNull
  @DecimalMin("0.00")
  private BigDecimal price;
}
