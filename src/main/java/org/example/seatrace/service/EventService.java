package org.example.seatrace.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.event.EventResponse;
import org.example.seatrace.dto.seat.EventSeatStats;
import org.example.seatrace.entity.Event;
import org.example.seatrace.entity.EventSeatStatus;
import org.example.seatrace.repository.EventRepository;
import org.example.seatrace.repository.EventSeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventService {

  private final EventRepository eventRepository;
  private final EventSeatRepository eventSeatRepository;

  @Transactional(readOnly = true)
  public List<EventResponse> listEvents() {
    List<Event> events = eventRepository.findAll();
    if (events.isEmpty()) {
      return List.of();
    }

    List<Long> eventIds = events.stream()
        .map(Event::getId)
        .collect(Collectors.toList());

    List<EventSeatStats> stats = eventSeatRepository.aggregateStatsByEventIds(eventIds,
        EventSeatStatus.AVAILABLE);
    Map<Long, EventSeatStats> statsByEvent = stats.stream()
        .collect(Collectors.toMap(EventSeatStats::getEventId, stat -> stat));

    return events.stream()
        .map(event -> EventResponse.from(event, statsByEvent.get(event.getId())))
        .collect(Collectors.toList());
  }
}
