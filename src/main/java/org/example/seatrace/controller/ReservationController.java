package org.example.seatrace.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.error.ApiErrorResponse;
import org.example.seatrace.dto.queue.QueueEnterResponse;
import org.example.seatrace.dto.queue.QueueStatusResponse;
import org.example.seatrace.dto.reservation.HoldSeatRequest;
import org.example.seatrace.dto.reservation.HoldSeatResponse;
import org.example.seatrace.dto.reservation.ReservationResponse;
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
@Tag(name = "Reservation", description = "좌석 홀드 및 예약 관리 API")
@SecurityRequirement(name = "bearerAuth")
public class ReservationController {

  private final ReservationService reservationService;
  private final VirtualQueueService virtualQueueService;

  @PostMapping("/events/{eventId}/holds")
  @Operation(summary = "좌석 홀드", description = "선택한 좌석을 홀드 상태로 생성합니다.")
  @ApiResponses({
      @ApiResponse(responseCode = "401", description = "인증 실패",
          content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "200", description = "홀드 성공"),
      @ApiResponse(responseCode = "400", description = "잘못된 요청",
          content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "409", description = "좌석 충돌 또는 상태 충돌",
          content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "429", description = "대기열 미통과",
          content = @Content(schema = @Schema(implementation = QueueEnterResponse.class)))
  })
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
  @Operation(summary = "대기열 진입", description = "이벤트 대기열에 진입합니다.")
  public ResponseEntity<QueueEnterResponse> enterQueue(
      @PathVariable Long eventId,
      @AuthenticationPrincipal CustomUserPrincipal principal
  ) {
    return ResponseEntity.ok(
        virtualQueueService.enterOrWait(eventId, principal.getUserId())
    );
  }

  @GetMapping("/events/{eventId}/queue/status")
  @Operation(summary = "대기열 상태 조회", description = "현재 사용자의 대기열 상태를 조회합니다.")
  public ResponseEntity<QueueStatusResponse> queueStatus(
      @PathVariable Long eventId,
      @AuthenticationPrincipal CustomUserPrincipal principal
  ) {
    return ResponseEntity.ok(
        virtualQueueService.status(eventId, principal.getUserId())
    );
  }

  @PostMapping("/reservations/{reservationId}/confirm")
  @Operation(summary = "예약 확정", description = "홀드된 예약을 확정 상태로 전환합니다.")
  @ApiResponses({
      @ApiResponse(responseCode = "401", description = "인증 실패",
          content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "200", description = "예약 확정 성공"),
      @ApiResponse(responseCode = "404", description = "예약을 찾을 수 없음",
          content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "409", description = "확정할 수 없는 예약 상태",
          content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  public ResponseEntity<ReservationResponse> confirmReservation(
      @PathVariable Long reservationId,
      @AuthenticationPrincipal CustomUserPrincipal principal
  ) {
    return ResponseEntity.ok(
        reservationService.confirmReservation(principal.getUserId(), reservationId)
    );
  }

  @PostMapping("/reservations/{reservationId}/cancel")
  @Operation(summary = "예약 취소", description = "예약을 취소하고 좌석을 다시 해제합니다.")
  @ApiResponses({
      @ApiResponse(responseCode = "401", description = "인증 실패",
          content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "200", description = "예약 취소 성공"),
      @ApiResponse(responseCode = "404", description = "예약을 찾을 수 없음",
          content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
      @ApiResponse(responseCode = "409", description = "취소할 수 없는 예약 상태",
          content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  })
  public ResponseEntity<ReservationResponse> cancelReservation(
      @PathVariable Long reservationId,
      @AuthenticationPrincipal CustomUserPrincipal principal
  ) {
    return ResponseEntity.ok(
        reservationService.cancelReservation(principal.getUserId(), reservationId)
    );
  }
}
