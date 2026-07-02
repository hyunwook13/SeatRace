package org.example.seatrace.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.event.EventCreateRequest;
import org.example.seatrace.dto.seat.EventSeatStats;
import org.example.seatrace.dto.event.EventResponse;
import org.example.seatrace.entity.Event;
import org.example.seatrace.entity.Venue;
import org.example.seatrace.repository.EventRepository;
import org.example.seatrace.repository.VenueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventAdminService {

  private final EventRepository eventRepository;
  private final VenueRepository venueRepository;

  @Transactional
  public EventResponse createEvent(EventCreateRequest request) {
    validateTimeRange(request.getStartAt(), request.getEndAt());
    Venue venue = venueRepository.findById(request.getVenueId())
        .orElseThrow(() -> new IllegalArgumentException("Venue not found"));

    Event event = eventRepository.save(Event.builder()
        .venue(venue)
        .name(request.getName())
        .startAt(request.getStartAt())
        .endAt(request.getEndAt())
        .status(request.getStatus() == null ? org.example.seatrace.entity.EventStatus.SCHEDULED
            : request.getStatus())
        .build());

    return EventResponse.from(event, EventSeatStats.emptyFor(event.getId()));
  }

  private void validateTimeRange(LocalDateTime start, LocalDateTime end) {
    if (start == null || end == null || !start.isBefore(end)) {
      throw new IllegalArgumentException("Start time must be before end time");
    }
  }
}
