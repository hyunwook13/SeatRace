package org.example.seatrace.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.seatrace.entity.User;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDto {
  private Long id;
  private String email;
  private String name;
  private String role;

  public static UserDto from(User user) {
    return UserDto.builder()
        .id(user.getId())
        .email(user.getEmail())
        .name(user.getName())
        .role(user.getRole().name())
        .build();
  }
}