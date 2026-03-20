package org.example.seatrace.controller;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.venue.BulkSeatCreateResponse;
import org.example.seatrace.dto.venue.SeatGenerateRequest;
import org.example.seatrace.dto.venue.VenueCreateRequest;
import org.example.seatrace.dto.venue.VenueResponse;
import org.example.seatrace.service.VenueAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/venues")
@SecurityRequirement(name = "bearerAuth")
@org.example.seatrace.security.AdminOnly
@RequiredArgsConstructor
public class AdminVenueController {

  private final VenueAdminService venueAdminService;

  @PostMapping
  public ResponseEntity<VenueResponse> createVenue(@RequestBody @Valid VenueCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(venueAdminService.createVenue(request));
  }

  @PostMapping("/{venueId}/seats/generate")
  public ResponseEntity<BulkSeatCreateResponse> generateSeats(@PathVariable Long venueId,
      @RequestBody @Valid SeatGenerateRequest request, Authentication authentication) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(venueAdminService.generateSeats(venueId, request,
            authentication == null ? null : authentication.getName()));
  }
}
