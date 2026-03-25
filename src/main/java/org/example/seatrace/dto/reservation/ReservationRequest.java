package org.example.seatrace.dto.reservation;

import java.util.List;

public record ReservationRequest(
    String userId,
    List<String> seatIds
) {

}
