package org.example.seatrace.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.seat.PhysicalSeatBulkCreateResponse;
import org.example.seatrace.dto.seat.SeatGenerateRequest;
import org.example.seatrace.dto.seat.SeatSectionCreateRequest;
import org.example.seatrace.dto.seat.SeatSectionResponse;
import org.example.seatrace.service.SeatSectionAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@SecurityRequirement(name = "bearerAuth")
@org.example.seatrace.security.AdminOnly
@RequiredArgsConstructor
public class AdminSeatSectionController {

  private final SeatSectionAdminService seatSectionAdminService;

  @PostMapping("/venues/{venueId}/seat-sections")
  public ResponseEntity<SeatSectionResponse> createSeatSection(
      @PathVariable Long venueId,
      @RequestBody @Valid SeatSectionCreateRequest request
  ) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(seatSectionAdminService.createSeatSection(venueId, request.getName()));
  }

  @PostMapping("/seat-sections/{seatSectionId}/seats/generate")
  public ResponseEntity<PhysicalSeatBulkCreateResponse> generateSeats(
      @PathVariable Long seatSectionId,
      @RequestBody @Valid SeatGenerateRequest request,
      Authentication authentication
  ) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(seatSectionAdminService.generateSeats(seatSectionId, request,
            authentication == null ? null : authentication.getName()));
  }
}
