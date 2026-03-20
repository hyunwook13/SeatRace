package org.example.seatrace.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.seatrace.dto.UserDto;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JwtDto {
  private String accessToken;
  private UserDto user;
}