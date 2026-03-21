package org.example.seatrace.security.jwt;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.util.Date;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

  private final JwtProperties jwtProperties;
  private JWSSigner signer;
  private JWSVerifier verifier;

  @PostConstruct
  public void init() {
    try {
      byte[] secretKey = jwtProperties.getSecret().getBytes();
      this.signer = new MACSigner(secretKey);
      this.verifier = new MACVerifier(secretKey);
    } catch (JOSEException e) {
      throw new RuntimeException("JWT Key Init Failed", e);
    }
  }

  public String generateToken(Long userId, String username, String role) {
    try {
      Date now = new Date();
      Date expirationTime = new Date(now.getTime() + jwtProperties.getAccessTokenValidityInMs());

      JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
          .issuer(jwtProperties.getIssuer())
          .subject(username)
          .issueTime(now)
          .expirationTime(expirationTime)
          .claim("userId", userId)
          .claim("role", role)
          .build();

      SignedJWT signedJWT = new SignedJWT(
          new JWSHeader(JWSAlgorithm.HS256),
          claimsSet
      );

      signedJWT.sign(signer);
      return signedJWT.serialize();
    } catch (JOSEException e) {
      log.error("Error generating token", e);
      throw new RuntimeException("Error generating token", e);
    }
  }

  public JWTClaimsSet getClaims(String token) {
    try {
      SignedJWT signedJWT = SignedJWT.parse(token);

      if (!signedJWT.verify(verifier)) {
        throw new RuntimeException("Invalid JWT Signature");
      }

      JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
      Date expirationTime = claims.getExpirationTime();

      if (expirationTime != null && expirationTime.before(new Date())) {
        throw new RuntimeException("Expired JWT Token");
      }

      return claims;
    } catch (ParseException | JOSEException e) {
      log.error("Invalid Token: {}", e.getMessage());
      throw new RuntimeException("Invalid JWT Token", e);
    }
  }

  public boolean validateToken(String token) {
    try {
      getClaims(token);
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
