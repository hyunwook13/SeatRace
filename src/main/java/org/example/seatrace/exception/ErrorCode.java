package org.example.seatrace.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
  // 400
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"),
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"),

  // 401/403
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"),
  FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN"),

  // 404
  RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND"),

  // 503
  REDIS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "REDIS_UNAVAILABLE"),

  // 409
  RESERVATION_CONFLICT(HttpStatus.CONFLICT, "RESERVATION_CONFLICT"),
  INVALID_STATE(HttpStatus.CONFLICT, "INVALID_STATE"),
  SEAT_ALREADY_TAKEN(HttpStatus.CONFLICT, "SEAT_ALREADY_TAKEN"),

  // 500
  INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR");

  private final HttpStatus status;
  private final String code;

  ErrorCode(HttpStatus status, String code) {
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }
}
