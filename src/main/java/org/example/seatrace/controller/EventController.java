package org.example.seatrace.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.seat.EventSeatListResponse;
import org.example.seatrace.dto.event.EventResponse;
import org.example.seatrace.service.EventSeatService;
import org.example.seatrace.service.EventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

  private final EventService eventService;
  private final EventSeatService eventSeatService;

  @GetMapping
  public ResponseEntity<List<EventResponse>> listEvents() {
    return ResponseEntity.ok(eventService.listEvents());
  }

  @GetMapping("/{eventId}/seats")
  public ResponseEntity<EventSeatListResponse> listSeats(@PathVariable Long eventId) {
    return ResponseEntity.ok(EventSeatListResponse.from(eventSeatService.listSeats(eventId)));
  }
}
