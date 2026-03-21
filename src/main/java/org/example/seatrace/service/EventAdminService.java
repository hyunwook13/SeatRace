package org.example.seatrace.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.event.EventCreateRequest;
import org.example.seatrace.dto.seat.EventSeatStats;
import org.example.seatrace.dto.event.EventResponse;
import org.example.seatrace.entity.Event;
import org.example.seatrace.entity.EventSeat;
import org.example.seatrace.entity.EventSeatStatus;
import org.example.seatrace.entity.Seat;
import org.example.seatrace.entity.Venue;
import org.example.seatrace.repository.EventRepository;
import org.example.seatrace.repository.EventSeatRepository;
import org.example.seatrace.repository.SeatRepository;
import org.example.seatrace.repository.VenueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventAdminService {

  private final EventRepository eventRepository;
  private final EventSeatRepository eventSeatRepository;
  private final VenueRepository venueRepository;
  private final SeatRepository seatRepository;

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

    List<Seat> seats = seatRepository.findAllByVenueId(venue.getId());
    if (seats.size() <= 0) {
      throw new IllegalArgumentException("좌석이 존재하지 않습니다.");
    }

    List<EventSeat> eventSeats = seats.stream()
        .map(seat -> buildEventSeat(event, seat))
        .collect(Collectors.toList());
    List<EventSeat> savedSeats = eventSeatRepository.saveAll(eventSeats);

    EventSeatStats stats = new EventSeatStats(event.getId(), (long) savedSeats.size(),
        (long) savedSeats.size());

    return EventResponse.from(event, stats);
  }

  private EventSeat buildEventSeat(Event event, Seat seat) {
    return EventSeat.builder()
        .event(event)
        .seat(seat)
        .status(EventSeatStatus.AVAILABLE)
        .heldUntil(null)
        .build();
  }

  private void validateTimeRange(LocalDateTime start, LocalDateTime end) {
    if (start == null || end == null || !start.isBefore(end)) {
      throw new IllegalArgumentException("Start time must be before end time");
    }
  }
}
