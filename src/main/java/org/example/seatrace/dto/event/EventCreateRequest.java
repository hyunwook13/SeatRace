package org.example.seatrace.dto.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
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

  private EventStatus status = EventStatus.SCHEDULED;
}
