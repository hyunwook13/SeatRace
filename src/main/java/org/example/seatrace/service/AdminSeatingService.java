package org.example.seatrace.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.seat.EventSeatBulkCreateResponse;
import org.example.seatrace.dto.seat.EventSeatSummary;
import org.example.seatrace.entity.Event;
import org.example.seatrace.entity.EventSeat;
import org.example.seatrace.entity.EventSeatStatus;
import org.example.seatrace.entity.Seat;
import org.example.seatrace.entity.SeatGrade;
import org.example.seatrace.entity.SeatSection;
import org.example.seatrace.exception.EmptyPhysicalSeatException;
import org.example.seatrace.repository.EventRepository;
import org.example.seatrace.repository.EventSeatRepository;
import org.example.seatrace.repository.SeatRepository;
import org.example.seatrace.repository.SeatSectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminSeatingService {

  private final EventRepository eventRepository;
  private final SeatSectionRepository seatSectionRepository;
  private final SeatRepository seatRepository;
  private final EventSeatRepository eventSeatRepository;

  @Transactional
  public EventSeatBulkCreateResponse createEventSeats(Long eventId, Long seatSectionId,
      SeatGrade grade, Long price) {
    Event event = eventRepository.findById(eventId)
        .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));

    SeatSection seatSection = seatSectionRepository.findById(seatSectionId)
        .orElseThrow(() -> new IllegalArgumentException("SeatSection not found: " + seatSectionId));

    if (!event.getVenue().getId().equals(seatSection.getVenue().getId())) {
      throw new IllegalArgumentException("Event and SeatSection belong to different venues.");
    }

    List<Seat> physicalSeats = seatRepository.findAllBySeatSectionId(seatSection.getId());

    if (physicalSeats.isEmpty()) {
      throw new EmptyPhysicalSeatException(
          "해당 공연장에 등록된 물리 좌석 정보가 없습니다. 먼저 물리 좌석 배치를 완료해 주세요."
      );
    }

    List<EventSeat> eventSeats = physicalSeats.stream()
        .map(seat -> EventSeat.builder()
            .event(event)
            .seat(seat)
            .status(EventSeatStatus.AVAILABLE)
            .heldUntil(null)
            .build())
        .peek(eventSeat -> eventSeat.applyCommercialTerms(grade, price))
        .toList();

    eventSeatRepository.saveAll(eventSeats);

    return EventSeatBulkCreateResponse.builder()
        .eventId(eventId)
        .seatSectionId(seatSectionId)
        .createdCount(eventSeats.size())
        .createdSeats(eventSeats.stream()
            .map(EventSeatSummary::from)
            .toList())
        .build();
  }
}
