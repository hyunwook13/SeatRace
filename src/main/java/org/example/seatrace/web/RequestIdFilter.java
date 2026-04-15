package org.example.seatrace.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  public static final String TRACE_ID_HEADER = "X-Trace-Id";

  public static final String REQUEST_ID_ATTR = "requestId";
  public static final String TRACE_ID_ATTR = "traceId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    String requestId = Optional.ofNullable(request.getHeader(REQUEST_ID_HEADER))
        .filter(StringUtils::hasText)
        .orElseGet(() -> UUID.randomUUID().toString());

    String traceId = Optional.ofNullable(request.getHeader(TRACE_ID_HEADER))
        .filter(StringUtils::hasText)
        .orElse(requestId);

    request.setAttribute(REQUEST_ID_ATTR, requestId);
    request.setAttribute(TRACE_ID_ATTR, traceId);
    response.setHeader(REQUEST_ID_HEADER, requestId);

    MDC.put(REQUEST_ID_ATTR, requestId);
    MDC.put(TRACE_ID_ATTR, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(REQUEST_ID_ATTR);
      MDC.remove(TRACE_ID_ATTR);
    }
  }
}

