package org.example.seatrace.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.seat.EventSeatListResponse;
import org.example.seatrace.dto.event.EventResponse;
import org.example.seatrace.security.CustomUserPrincipal;
import org.example.seatrace.service.EventSeatService;
import org.example.seatrace.service.EventService;
import org.example.seatrace.service.VirtualQueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

  private final EventService eventService;
  private final EventSeatService eventSeatService;
  private final VirtualQueueService virtualQueueService;

  @GetMapping
  public ResponseEntity<List<EventResponse>> listEvents() {
    return ResponseEntity.ok(eventService.listEvents());
  }

  @GetMapping("/{eventId}/seats")
  public ResponseEntity<?> listSeats(
      @PathVariable Long eventId,
      @RequestHeader(value = "X-Queue-Token", required = false) String queueToken,
      @AuthenticationPrincipal CustomUserPrincipal principal
  ) {
    if (principal == null) {
      return ResponseEntity.status(401).build();
    }

    if (!virtualQueueService.isAdmitted(eventId, principal.getUserId(), queueToken)) {
      return ResponseEntity.status(429).body(virtualQueueService.notAdmittedResponse(eventId));
    }

    return ResponseEntity.ok(EventSeatListResponse.from(eventSeatService.listSeats(eventId)));
  }
}
