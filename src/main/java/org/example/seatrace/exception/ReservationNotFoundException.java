package org.example.seatrace.exception;

public class ReservationNotFoundException extends AppException {

  public ReservationNotFoundException(String message) {
    super(ErrorCode.RESERVATION_NOT_FOUND, message);
  }
}
