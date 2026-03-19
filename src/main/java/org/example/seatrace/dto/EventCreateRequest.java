package org.example.seatrace.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.seatrace.entity.EventStatus;

@Getter
@Setter
@NoArgsConstructor
public class EventCreateRequest {

  @NotNull
  private Long venueId;

  @NotBlank
  private String name;

  @NotNull
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
  private LocalDateTime startAt;

  @NotNull
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
  private LocalDateTime endAt;

  @Valid
  @NotEmpty
  private List<EventSeatPricingRequest> seatPricing;

  private EventStatus status = EventStatus.SCHEDULED;
}
