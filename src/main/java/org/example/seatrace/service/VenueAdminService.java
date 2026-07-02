package org.example.seatrace.service;

import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.venue.VenueCreateRequest;
import org.example.seatrace.dto.venue.VenueResponse;
import org.example.seatrace.entity.Venue;
import org.example.seatrace.repository.VenueRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class VenueAdminService {

  private final VenueRepository venueRepository;

  public VenueResponse createVenue(VenueCreateRequest request) {
    venueRepository.findByLocation(request.getLocation()).ifPresent(venue -> {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 공연장 위치입니다.");
    });

    Venue venue = venueRepository.save(Venue.builder()
        .name(request.getName())
        .location(request.getLocation())
        .build());
    return VenueResponse.from(venue);
  }
}
