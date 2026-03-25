package org.example.seatrace.dto.reservation;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.example.seatrace.entity.ReservationStatus;

@Getter
@Builder
@AllArgsConstructor
public class HoldSeatResponse {

  private Long reservationId;

  private Long eventId;

  private List<Long> seatIds;

  private ReservationStatus status;

  private LocalDateTime expiresAt;
}