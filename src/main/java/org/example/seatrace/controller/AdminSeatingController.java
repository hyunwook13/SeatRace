package org.example.seatrace.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.seat.EventSeatBulkCreateResponse;
import org.example.seatrace.dto.seat.EventSeatCreateRequest;
import org.example.seatrace.service.AdminSeatingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/events")
@SecurityRequirement(name = "bearerAuth")
@org.example.seatrace.security.AdminOnly
@RequiredArgsConstructor
public class AdminSeatingController {

  private final AdminSeatingService adminSeatingService;

  @PostMapping("/{eventId}/seat-sections/{seatSectionId}/event-seats")
  public ResponseEntity<EventSeatBulkCreateResponse> createEventSeats(
      @PathVariable Long eventId,
      @PathVariable Long seatSectionId,
      @RequestBody @Valid EventSeatCreateRequest request
  ) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(adminSeatingService.createEventSeats(eventId, seatSectionId, request.getGrade(),
            request.getPrice()));
  }
}
