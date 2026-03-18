package org.example.seatrace.config;

import lombok.RequiredArgsConstructor;
import org.example.seatrace.entity.User;
import org.example.seatrace.entity.UserRole;
import org.example.seatrace.security.RestAuthenticationFailureHandler;
import org.example.seatrace.security.RestAuthenticationSuccessHandler;
import org.example.seatrace.user.CustomUserDetailsService;
import org.example.seatrace.user.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

  private final RestAuthenticationSuccessHandler restAuthenticationSuccessHandler;
  private final RestAuthenticationFailureHandler restAuthenticationFailureHandler;
  private final CustomUserDetailsService customUserDetailsService;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .userDetailsService(customUserDetailsService)
        .formLogin(form -> form
            .loginProcessingUrl("/login")
            .successHandler(restAuthenticationSuccessHandler)
            .failureHandler(restAuthenticationFailureHandler)
        )
        .sessionManagement(session -> session
            .maximumSessions(1)
            .maxSessionsPreventsLogin(false)
            .expiredUrl("/login")
        );

    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public CommandLineRunner initData(UserRepository userRepository, PasswordEncoder passwordEncoder) {
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
