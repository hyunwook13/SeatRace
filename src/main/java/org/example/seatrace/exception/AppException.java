package org.example.seatrace.exception;

public class AppException extends RuntimeException {

  private final ErrorCode errorCode;
  private final Object details;

  public AppException(ErrorCode errorCode, String message) {
    this(errorCode, message, null, null);
  }

  public AppException(ErrorCode errorCode, String message, Object details) {
    this(errorCode, message, details, null);
  }

  public AppException(ErrorCode errorCode, String message, Object details, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
    this.details = details;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }

  public Object getDetails() {
    return details;
  }
}

