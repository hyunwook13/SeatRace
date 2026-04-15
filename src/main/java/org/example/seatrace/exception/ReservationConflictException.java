package org.example.seatrace.exception;

public class ReservationConflictException extends AppException {

  public ReservationConflictException(String message) {
    super(ErrorCode.RESERVATION_CONFLICT, message);
  }
}
