package org.example.seatrace.security.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jwt")
class JwtProperties {
  private String secret;
  private String issuer;
  private long accessTokenValidityInMs;
  private long refreshTokenValidityInMs;
}
