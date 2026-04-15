package org.example.seatrace.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.dto.error.ApiErrorResponse;
import org.example.seatrace.web.RequestIdFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(AppException.class)
  public ResponseEntity<ApiErrorResponse> handleAppException(
      AppException ex,
      HttpServletRequest request
  ) {
    return build(ex.getErrorCode().status(), ex.getErrorCode().code(), ex.getMessage(), request,
        ex.getDetails());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleValidation(
      MethodArgumentNotValidException ex,
      HttpServletRequest request
  ) {
    List<Map<String, Object>> details = ex.getBindingResult().getFieldErrors().stream()
        .map(err -> Map.<String, Object>of(
            "field", err.getField(),
            "rejectedValue", err.getRejectedValue(),
            "message", err.getDefaultMessage()
        ))
        .toList();

    return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.code(), "요청 값이 올바르지 않습니다.", request,
        details);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
      IllegalArgumentException ex,
      HttpServletRequest request
  ) {
    return build(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST.code(), ex.getMessage(), request, null);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ApiErrorResponse> handleIllegalState(
      IllegalStateException ex,
      HttpServletRequest request
  ) {
    return build(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE.code(), ex.getMessage(), request, null);
  }

  @ExceptionHandler({
      RedisUnavailableException.class,
      RedisConnectionFailureException.class,
      RedisSystemException.class,
      RedisConnectionException.class,
      RedisCommandTimeoutException.class
  })
  public ResponseEntity<ApiErrorResponse> handleRedisUnavailable(Exception ex, HttpServletRequest request) {
    log.warn("Redis unavailable: {}", ex.getMessage());
    return build(
        HttpStatus.SERVICE_UNAVAILABLE,
        ErrorCode.REDIS_UNAVAILABLE.code(),
        "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해주세요.",
        request,
        null
    );
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Unhandled exception", ex);
    return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR.code(),
        "서버 내부 오류가 발생했습니다.", request, null);
  }

  private ResponseEntity<ApiErrorResponse> build(
      HttpStatus status,
      String code,
      String message,
      HttpServletRequest request,
      Object details
  ) {
    String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
    String traceId = (String) request.getAttribute(RequestIdFilter.TRACE_ID_ATTR);

    return ResponseEntity.status(status)
        .body(ApiErrorResponse.builder()
            .timestamp(OffsetDateTime.now())
            .status(status.value())
            .code(code)
            .message(message)
            .path(request.getRequestURI())
            .requestId(requestId)
            .traceId(traceId)
            .details(details)
            .build());
  }
}
