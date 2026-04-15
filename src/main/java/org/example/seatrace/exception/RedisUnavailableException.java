package org.example.seatrace.exception;

public class RedisUnavailableException extends AppException {

  public RedisUnavailableException(String message) {
    super(ErrorCode.REDIS_UNAVAILABLE, message);
  }

  public RedisUnavailableException(String message, Throwable cause) {
    super(ErrorCode.REDIS_UNAVAILABLE, message, null, cause);
  }
}

