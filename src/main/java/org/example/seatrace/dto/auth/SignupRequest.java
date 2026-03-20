package org.example.seatrace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SignupRequest {

  @Email
  @NotBlank
  private String email;

  @NotBlank
  @Size(min = 2, max = 20)
  private String name;

  @NotBlank
  @Size(min = 4, max = 100)
  private String password;
}
