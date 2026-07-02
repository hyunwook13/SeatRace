package org.example.seatrace.controller;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.venue.VenueCreateRequest;
import org.example.seatrace.dto.venue.VenueResponse;
import org.example.seatrace.service.VenueAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
}
