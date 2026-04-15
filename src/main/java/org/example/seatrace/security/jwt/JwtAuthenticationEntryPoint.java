package org.example.seatrace.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.error.ApiErrorResponse;
import org.example.seatrace.web.RequestIdFilter;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException
  ) throws IOException, ServletException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    objectMapper.writeValue(response.getWriter(), ApiErrorResponse.builder()
        .timestamp(OffsetDateTime.now())
        .status(HttpServletResponse.SC_UNAUTHORIZED)
        .code("UNAUTHORIZED")
        .message(Optional.ofNullable(authException.getMessage())
            .orElse("인증이 필요합니다."))
        .path(request.getRequestURI())
        .requestId((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR))
        .traceId((String) request.getAttribute(RequestIdFilter.TRACE_ID_ATTR))
        .details(null)
        .build());
  }
}
