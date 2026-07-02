package org.example.seatrace.config;

import lombok.RequiredArgsConstructor;
import org.example.seatrace.entity.User;
import org.example.seatrace.entity.UserRole;
import org.example.seatrace.security.jwt.JwtLoginSuccessHandler;
import org.example.seatrace.security.RestAuthenticationFailureHandler;
import org.example.seatrace.security.RestAuthenticationSuccessHandler;
import org.example.seatrace.security.jwt.JwtAuthenticationFilter;
import org.example.seatrace.security.jwt.JwtTokenProvider;
import org.example.seatrace.security.CustomUserDetailsService;
import org.example.seatrace.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final RestAuthenticationSuccessHandler restAuthenticationSuccessHandler;
  private final RestAuthenticationFailureHandler restAuthenticationFailureHandler;
  private final JwtLoginSuccessHandler jwtLoginSuccessHandler;
  private final CustomUserDetailsService customUserDetailsService;
  private final JwtTokenProvider jwtTokenProvider;
  private final AuthenticationEntryPoint jwtAuthenticationEntryPoint;

  @Bean
  public WebSecurityCustomizer webSecurityCustomizer() {
    return (web) -> web.ignoring()
        .requestMatchers("/actuator/**")
        // 💡 Scouter 에이전트 및 내부 Jetty 수집 관련 정적 파일과 서블릿 경로를 시큐리티 필터 자체에서 완전히 무시하도록 추가
        .requestMatchers("/scouter/**", "/scouter/api/**", "/main.html", "/favicon.ico");
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

    http
        .csrf(AbstractHttpConfigurer::disable)
        .userDetailsService(customUserDetailsService)
        .formLogin(form -> form
            .loginPage("/login")
            .loginProcessingUrl("/login")
            .permitAll()
            .successHandler(jwtLoginSuccessHandler)
            .failureHandler(restAuthenticationFailureHandler)
        )
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/**").permitAll()
            // 💡 HTTP 접근 제어 단계에서도 외부 대시보드의 Web API 바인딩 요청을 무조건 통과하도록 명시
            .requestMatchers("/scouter/**", "/scouter/api/**", "/main.html").permitAll()
            .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .requestMatchers(HttpMethod.GET, "/api/events").permitAll()
            .requestMatchers("/api/events/**").authenticated()
            .requestMatchers("/api/reservations/**").authenticated()
            .anyRequest().permitAll()
        )
        .sessionManagement(session -> session
            .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .sessionFixation(fixation -> fixation.changeSessionId())
            .maximumSessions(1)
            .maxSessionsPreventsLogin(false)
            .expiredUrl("/login")
        )
        .exceptionHandling(exception -> exception
            .authenticationEntryPoint(jwtAuthenticationEntryPoint)
        )
        .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
            UsernamePasswordAuthenticationFilter.class
        );

    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public CommandLineRunner initData(UserRepository userRepository,
      PasswordEncoder passwordEncoder) {
    return args -> {
      if (userRepository.findByEmail("user").isEmpty()) {
        userRepository.save(User.builder()
            .email("user")
            .name("user")
            .passwordHash(passwordEncoder.encode("1234"))
            .role(UserRole.USER)
            .build());
      }
      if (userRepository.findByEmail("admin").isEmpty()) {
        userRepository.save(User.builder()
            .email("admin")
            .name("admin")
            .passwordHash(passwordEncoder.encode("1234"))
            .role(UserRole.ADMIN)
            .build());
      }
    };
  }
}