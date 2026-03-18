package org.example.seatrace.config;

import lombok.RequiredArgsConstructor;
import org.example.seatrace.security.RestAuthenticationFailureHandler;
import org.example.seatrace.security.RestAuthenticationSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

  private final RestAuthenticationSuccessHandler restAuthenticationSuccessHandler;
  private final RestAuthenticationFailureHandler restAuthenticationFailureHandler;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
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

}
