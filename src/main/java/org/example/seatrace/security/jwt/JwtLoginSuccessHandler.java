package org.example.seatrace.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.JwtDto;
import org.example.seatrace.dto.UserDto;
import org.example.seatrace.entity.User;
import org.example.seatrace.repository.UserRepository;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtLoginSuccessHandler implements AuthenticationSuccessHandler {

  private final UserRepository userRepository;

  private final JwtTokenProvider jwtTokenProvider;
  private final ObjectMapper objectMapper;

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
      Authentication authentication) throws IOException, ServletException {
    UserDetails details = (UserDetails) authentication.getPrincipal();

    User user = userRepository.findByEmail(details.getUsername())
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));

    String accessToken = jwtTokenProvider.generateToken(user.getName(), user.getRole().toString());
    String refreshToken = jwtTokenProvider.generateToken(user.getName(), user.getRole().toString());

    Cookie refreshCookie = new Cookie("REFRESH_TOKEN", refreshToken);
    refreshCookie.setHttpOnly(true);
    refreshCookie.setPath("/");
    refreshCookie.setMaxAge(60 * 60 * 24 * 14); // 2주
    response.addCookie(refreshCookie);

    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());

    JwtDto jwtDto = JwtDto.builder()
        .accessToken(accessToken)
        .user(UserDto.from(user))
        .build();

    response.getWriter().write(objectMapper.writeValueAsString(jwtDto));
  }
}
