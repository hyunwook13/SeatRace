package org.example.seatrace.dto.error;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(name = "ApiErrorResponse", description = "API 오류 응답")
public class ApiErrorResponse {

  @Schema(description = "오류 발생 시각")
  private final OffsetDateTime timestamp;

  @Schema(description = "HTTP 상태 코드")
  private final int status;

  @Schema(description = "오류 코드")
  private final String code;

  @Schema(description = "오류 메시지")
  private final String message;

  @Schema(description = "요청 경로")
  private final String path;

  @Schema(description = "요청 식별자 (X-Request-Id)")
  private final String requestId;

  @Schema(description = "추적 식별자 (분산 추적 연동 시 사용)")
  private final String traceId;

  @Schema(description = "추가 정보 (검증 실패 필드 목록 등)")
  private final Object details;
}
