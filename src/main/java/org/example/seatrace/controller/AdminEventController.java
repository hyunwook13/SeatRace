package org.example.seatrace.controller;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.event.EventCreateRequest;
import org.example.seatrace.dto.event.EventResponse;
import org.example.seatrace.service.EventAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/events")
@SecurityRequirement(name = "bearerAuth")
@org.example.seatrace.security.AdminOnly
@RequiredArgsConstructor
public class AdminEventController {

  private final EventAdminService eventAdminService;

  @PostMapping
  public ResponseEntity<EventResponse> createEvent(@RequestBody @Valid EventCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(eventAdminService.createEvent(request));
  }
}
