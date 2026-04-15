package org.example.seatrace.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.error.ApiErrorResponse;
import org.example.seatrace.web.RequestIdFilter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RestAuthenticationFailureHandler implements AuthenticationFailureHandler {

  private final ObjectMapper objectMapper;

  @Override
  public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException exception) throws IOException, ServletException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType("application/json");
    objectMapper.writeValue(response.getWriter(), ApiErrorResponse.builder()
        .timestamp(OffsetDateTime.now())
        .status(HttpStatus.UNAUTHORIZED.value())
        .code("LOGIN_FAILED")
        .message("Login Failed")
        .path(request.getRequestURI())
        .requestId((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTR))
        .traceId((String) request.getAttribute(RequestIdFilter.TRACE_ID_ATTR))
        .details(null)
        .build());
  }
}
