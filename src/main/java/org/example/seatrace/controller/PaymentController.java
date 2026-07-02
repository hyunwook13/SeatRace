package org.example.seatrace.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.payment.PaymentConfirmRequest;
import org.example.seatrace.dto.payment.PaymentConfirmResponse;
import org.example.seatrace.dto.payment.PaymentOrderResponse;
import org.example.seatrace.security.CustomUserPrincipal;
import org.example.seatrace.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class PaymentController {

  private final PaymentService paymentService;

  @PostMapping("/reservations/{reservationId}/orders")
  public ResponseEntity<PaymentOrderResponse> createPaymentOrder(
      @PathVariable Long reservationId,
      @AuthenticationPrincipal CustomUserPrincipal principal
  ) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(paymentService.createPaymentOrder(principal.getUserId(), reservationId));
  }

  @PostMapping("/confirm")
  public ResponseEntity<PaymentConfirmResponse> confirmPayment(
      @RequestBody @Valid PaymentConfirmRequest request,
      @AuthenticationPrincipal CustomUserPrincipal principal
  ) {
    return ResponseEntity.ok(paymentService.confirmPayment(principal.getUserId(), request));
  }

  @PostMapping("/fail")
  public ResponseEntity<PaymentConfirmResponse> failPayment(
      @RequestParam String orderId,
      @RequestParam(required = false) String message,
      @AuthenticationPrincipal CustomUserPrincipal principal
  ) {
    return ResponseEntity.ok(
        paymentService.failPayment(principal.getUserId(), orderId, message == null ? "" : message)
    );
  }
}
