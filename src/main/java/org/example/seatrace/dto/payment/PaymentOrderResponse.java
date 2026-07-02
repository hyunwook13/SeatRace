package org.example.seatrace.dto.payment;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentOrderResponse {

  private final Long reservationId;
  private final String orderId;
  private final Long amount;
  private final String orderName;
}
