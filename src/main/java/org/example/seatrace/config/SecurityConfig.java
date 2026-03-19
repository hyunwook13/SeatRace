package org.example.seatrace.config;

import lombok.RequiredArgsConstructor;
import org.example.seatrace.entity.User;
import org.example.seatrace.entity.UserRole;
import org.example.seatrace.security.jwt.JwtLoginSuccessHandler;
import org.example.seatrace.security.RestAuthenticationFailureHandler;
import org.example.seatrace.security.RestAuthenticationSuccessHandler;
import org.example.seatrace.security.jwt.JwtAuthenticationFilter;
import org.example.seatrace.security.jwt.JwtTokenProvider;
import org.example.seatrace.user.CustomUserDetailsService;
import org.example.seatrace.user.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
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
            .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .requestMatchers(HttpMethod.GET, "/api/posts/**").permitAll()
            .requestMatchers("/api/posts/**").authenticated()
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
