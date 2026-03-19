package org.example.seatrace.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class VenueCreateRequest {

  @NotBlank
  private String name;

  @NotBlank
  private String location;
}
