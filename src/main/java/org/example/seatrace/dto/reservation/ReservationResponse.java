package org.example.seatrace.dto.reservation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.example.seatrace.entity.ReservationStatus;
import org.example.seatrace.dto.seat.SeatSummary;

@Getter
@Builder
@AllArgsConstructor
@Schema(name = "ReservationResponse", description = "예약 상세 응답")
public class ReservationResponse {

  @Schema(description = "예약 ID")
  private Long reservationId;

  @Schema(description = "예약자 ID")
  private Long userId;

  @Schema(description = "이벤트 ID")
  private Long eventId;

  @Schema(description = "이벤트명")
  private String eventName;

  @Schema(description = "공연장 ID")
  private Long venueId;

  @Schema(description = "공연장명")
  private String venueName;

  @Schema(description = "좌석 ID 목록")
  private List<Long> seatIds;

  @Schema(description = "좌석 상세 목록")
  private List<SeatSummary> seats;

  @Schema(description = "예약 상태")
  private ReservationStatus status;

  @Schema(description = "홀드 만료 시각")
  private LocalDateTime expiresAt;

  @Schema(description = "생성 시각")
  private LocalDateTime createdAt;

  @Schema(description = "수정 시각")
  private LocalDateTime updatedAt;
}
