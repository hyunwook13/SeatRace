package org.example.seatrace.security.jwt;

import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.entity.UserRole;
import org.example.seatrace.security.CustomUserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtTokenProvider jwtTokenProvider;
  private static final String HEADER_AUTHORIZATION = "Authorization";
  private static final String TOKEN_PREFIX = "Bearer ";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

    String token = resolveToken(request);

    if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
      JWTClaimsSet claims = jwtTokenProvider.getClaims(token);
      String username = claims.getSubject();

      try {
        Long userId = claims.getLongClaim("userId");
        String roleStr = claims.getStringClaim("role");
        
        if (userId != null && roleStr != null) {
          UserRole role = UserRole.valueOf(roleStr.replace("ROLE_", ""));

          CustomUserPrincipal principal = new CustomUserPrincipal(userId, username, "", role);
          Authentication authentication = new UsernamePasswordAuthenticationToken(
              principal, token, principal.getAuthorities());

          SecurityContextHolder.getContext().setAuthentication(authentication);
          log.debug("Security Context에 '{}' 인증 정보를 저장했습니다.", username);
        }
      } catch (Exception e) {
        log.error("JWT claims parsing error: {}", e.getMessage());
      }
    }

    filterChain.doFilter(request, response);
  }

  private String resolveToken(HttpServletRequest request) {
    String bearerToken = request.getHeader(HEADER_AUTHORIZATION);
    if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(TOKEN_PREFIX)) {
      return bearerToken.substring(TOKEN_PREFIX.length());
    }
    return null;
  }
}
