package org.example.seatrace.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.venue.BulkSeatCreateResponse;
import org.example.seatrace.dto.venue.SeatGenerateRequest;
import org.example.seatrace.dto.SeatSummary;
import org.example.seatrace.dto.venue.VenueCreateRequest;
import org.example.seatrace.dto.venue.VenueResponse;
import org.example.seatrace.entity.Seat;
import org.example.seatrace.entity.SeatAuditLog;
import org.example.seatrace.entity.Venue;
import org.example.seatrace.repository.SeatAuditLogRepository;
import org.example.seatrace.repository.SeatRepository;
import org.example.seatrace.repository.VenueRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class VenueAdminService {

  private final VenueRepository venueRepository;
  private final SeatRepository seatRepository;
  private final SeatAuditLogRepository seatAuditLogRepository;

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

  @Transactional
  public BulkSeatCreateResponse generateSeats(Long venueId, SeatGenerateRequest request, String actor) {
    Venue venue = venueRepository.findById(venueId)
        .orElseThrow(() -> new IllegalArgumentException("Venue not found"));

    if (request.getCount() < 1) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "count는 1 이상이어야 합니다.");
    }

    String section = request.getSection();
    String grade = request.getGrade();
    int createdCount = 0;
    int skipped = 0;
    int cursor = 1;
    List<SeatSummary> created = new java.util.ArrayList<>();
    int rowSize = 50;

    while (createdCount < request.getCount()) {
      int row = ((cursor - 1) / rowSize) + 1;
      int seatNumber = ((cursor - 1) % rowSize) + 1;
      String rowNo = leftPad(row, 2);
      String seatNo = leftPad(seatNumber, 3);

      if (seatRepository.existsByVenueIdAndSectionAndRowNoAndSeatNo(venueId, section, rowNo, seatNo)) {
        skipped++;
        cursor++;
        if (cursor > request.getCount() + 10000) {
          throw new ResponseStatusException(HttpStatus.CONFLICT, "생성 가능한 좌석 번호가 부족합니다.");
        }
        continue;
      }

      try {
        Seat seat = seatRepository.save(Seat.builder()
            .venue(venue)
            .section(section)
            .rowNo(rowNo)
            .seatNo(seatNo)
            .grade(grade)
            .build());

        created.add(SeatSummary.from(seat));
        createdCount++;
      } catch (DataIntegrityViolationException ex) {
        skipped++;
      } finally {
        cursor++;
      }
    }

    seatAuditLogRepository.save(SeatAuditLog.builder()
        .venue(venue)
        .action("AUTO_GENERATE")
        .actor(actor == null || actor.isBlank() ? "unknown" : actor)
        .detail(String.format("section=%s,grade=%s,requested=%d,created=%d,skipped=%d",
            section, grade, request.getCount(), createdCount, skipped))
        .build());

    return BulkSeatCreateResponse.builder()
        .venueId(venueId)
        .requestedCount(request.getCount())
        .createdCount(createdCount)
        .skippedCount(skipped)
        .createdSeats(created)
        .build();
  }

  private String leftPad(int value, int width) {
    return String.format("%0" + width + "d", value);
  }
}
