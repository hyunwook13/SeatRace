package org.example.seatrace.dto.payment;

import lombok.Builder;
import lombok.Getter;
import org.example.seatrace.dto.reservation.ReservationResponse;

@Getter
@Builder
public class PaymentConfirmResponse {

  private final String orderId;
  private final String paymentKey;
  private final Long amount;
  private final ReservationResponse reservation;
}
