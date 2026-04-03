package org.example.seatrace.controller;

import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.queue.QueueEnterResponse;
import org.example.seatrace.dto.queue.QueueStatusResponse;
import org.example.seatrace.dto.reservation.HoldSeatRequest;
import org.example.seatrace.dto.reservation.HoldSeatResponse;
import org.example.seatrace.security.CustomUserPrincipal;
import org.example.seatrace.service.ReservationService;
import org.example.seatrace.service.VirtualQueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReservationController {

  private final ReservationService reservationService;
  private final VirtualQueueService virtualQueueService;

  @PostMapping("/events/{eventId}/holds")
  public ResponseEntity<?> holdSeats(
      @PathVariable Long eventId,
      @AuthenticationPrincipal CustomUserPrincipal principal,
      @RequestBody HoldSeatRequest request
  ) {
    QueueEnterResponse queue = virtualQueueService.enterOrWait(eventId, principal.getUserId());
    if (!queue.admitted()) {
      return ResponseEntity.status(429).body(queue);
    }
    return ResponseEntity.ok(
        reservationService.holdSeats(principal.getUserId(), eventId, request)
    );
  }

  @PostMapping("/events/{eventId}/queue/enter")
  public ResponseEntity<QueueEnterResponse> enterQueue(
      @PathVariable Long eventId,
      @AuthenticationPrincipal CustomUserPrincipal principal
  ) {
    return ResponseEntity.ok(
        virtualQueueService.enterOrWait(eventId, principal.getUserId())
    );
  }

  @GetMapping("/events/{eventId}/queue/status")
  public ResponseEntity<QueueStatusResponse> queueStatus(
      @PathVariable Long eventId,
      @AuthenticationPrincipal CustomUserPrincipal principal
  ) {
    return ResponseEntity.ok(
        virtualQueueService.status(eventId, principal.getUserId())
    );
  }

//  @PostMapping("/reservations/{reservationId}/confirm")
//  public ResponseEntity<ReservationResponse> confirmReservation(
//      @PathVariable Long reservationId,
//      @AuthenticationPrincipal CustomUserPrincipal principal,
//      @RequestBody(required = false) ConfirmReservationRequest request
//  ) {
//    return ResponseEntity.ok(
//        reservationService.confirmReservation(principal.getUserId(), reservationId, request)
//    );
//  }
//
//  @PostMapping("/reservations/{reservationId}/cancel")
//  public ResponseEntity<Void> cancelReservation(
//      @PathVariable Long reservationId,
//      @AuthenticationPrincipal CustomUserPrincipal principal
//  ) {
//    reservationService.cancelReservation(principal.getUserId(), reservationId);
//    return ResponseEntity.noContent().build();
//  }
//
//  @GetMapping("/reservations/{reservationId}")
//  public ResponseEntity<ReservationResponse> getReservation(
//      @PathVariable Long reservationId,
//      @AuthenticationPrincipal CustomUserPrincipal principal
//  ) {
//    return ResponseEntity.ok(
//        reservationService.getReservation(principal.getUserId(), reservationId)
//    );
//  }
//
//  @GetMapping("/reservations/me")
//  public ResponseEntity<List<ReservationResponse>> getMyReservations(
//      @AuthenticationPrincipal CustomUserPrincipal principal
//  ) {
//    return ResponseEntity.ok(
//        reservationService.getMyReservations(principal.getUserId())
//    );
//  }
}
