package org.example.seatrace.exception;

public class SeatAlreadyTakenException extends AppException {
  public SeatAlreadyTakenException(String message) {
    super(ErrorCode.SEAT_ALREADY_TAKEN, message);
  }

  public SeatAlreadyTakenException(String message, Throwable cause) {
    super(ErrorCode.SEAT_ALREADY_TAKEN, message, null, cause);
  }
}
