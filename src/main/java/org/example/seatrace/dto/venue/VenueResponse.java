package org.example.seatrace.dto.venue;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.entity.Venue;

@Getter
@RequiredArgsConstructor
public class VenueResponse {

  private final Long id;
  private final String name;
  private final String location;

  public static VenueResponse from(Venue venue) {
    return new VenueResponse(venue.getId(), venue.getName(), venue.getLocation());
  }
}
