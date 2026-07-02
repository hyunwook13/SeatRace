package org.example.seatrace.dto.payment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PaymentConfirmRequest {

  @NotBlank
  private String orderId;

  @NotBlank
  private String paymentKey;

  @NotNull
  @Min(0)
  private Long amount;
}
