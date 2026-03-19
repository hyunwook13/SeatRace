package org.example.seatrace.dto;

import lombok.Builder;
import lombok.Getter;
import org.example.seatrace.entity.User;

@Getter
@Builder
public class SignupResponse {

  private final Long id;
  private final String email;
  private final String name;
  private final String role;

  public static SignupResponse from(User user) {
    return SignupResponse.builder()
        .id(user.getId())
        .email(user.getEmail())
        .name(user.getName())
        .role(user.getRole().name())
        .build();
  }
}
