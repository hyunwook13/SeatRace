package org.example.seatrace.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.seat.EventSeatSummary;
import org.example.seatrace.entity.Event;
import org.example.seatrace.repository.EventRepository;
import org.example.seatrace.repository.EventSeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventSeatService {

  private final EventRepository eventRepository;
  private final EventSeatRepository eventSeatRepository;

  @Transactional(readOnly = true)
  public List<EventSeatSummary> listSeats(Long eventId) {
    Event event = eventRepository.findById(eventId)
        .orElseThrow(() -> new IllegalArgumentException("Event not found"));
    return eventSeatRepository.findAllByEvent(event).stream()
        .map(EventSeatSummary::from)
        .toList();
  }
}
