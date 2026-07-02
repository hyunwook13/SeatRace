package org.example.seatrace.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.seat.PhysicalSeatBulkCreateResponse;
import org.example.seatrace.dto.seat.SeatGenerateRequest;
import org.example.seatrace.dto.seat.SeatSectionResponse;
import org.example.seatrace.dto.seat.SeatSummary;
import org.example.seatrace.entity.Seat;
import org.example.seatrace.entity.SeatAuditLog;
import org.example.seatrace.entity.SeatSection;
import org.example.seatrace.entity.Venue;
import org.example.seatrace.repository.SeatAuditLogRepository;
import org.example.seatrace.repository.SeatRepository;
import org.example.seatrace.repository.SeatSectionRepository;
import org.example.seatrace.repository.VenueRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SeatSectionAdminService {

  private final VenueRepository venueRepository;
  private final SeatSectionRepository seatSectionRepository;
  private final SeatRepository seatRepository;
  private final SeatAuditLogRepository seatAuditLogRepository;

  @Transactional
  public SeatSectionResponse createSeatSection(Long venueId, String name) {
    Venue venue = venueRepository.findById(venueId)
        .orElseThrow(() -> new IllegalArgumentException("Venue not found"));

    String normalizedName = name == null ? "" : name.trim();
    if (normalizedName.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "구역명은 비어 있을 수 없습니다.");
    }
    if (seatSectionRepository.existsByVenueIdAndName(venue.getId(), normalizedName)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 존재하는 구역명입니다.");
    }

    SeatSection seatSection = seatSectionRepository.save(SeatSection.builder()
        .venue(venue)
        .name(normalizedName)
        .build());
    return SeatSectionResponse.from(seatSection);
  }

  @Transactional
  public PhysicalSeatBulkCreateResponse generateSeats(Long seatSectionId,
      SeatGenerateRequest request, String actor) {
    SeatSection seatSection = seatSectionRepository.findById(seatSectionId)
        .orElseThrow(() -> new IllegalArgumentException("SeatSection not found"));

    if (request.getCount() < 1) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "count는 1 이상이어야 합니다.");
    }

    int createdCount = 0;
    int skipped = 0;
    int cursor = 1;
    int rowSize = 50;
    List<SeatSummary> created = new java.util.ArrayList<>();

    while (createdCount < request.getCount()) {
      int row = ((cursor - 1) / rowSize) + 1;
      int seatNumber = ((cursor - 1) % rowSize) + 1;
      String seatRow = leftPad(row, 2);

      if (seatRepository.existsBySeatSectionIdAndSeatRowAndSeatNumber(seatSectionId, seatRow,
          seatNumber)) {
        skipped++;
        cursor++;
        if (cursor > request.getCount() + 10000) {
          throw new ResponseStatusException(HttpStatus.CONFLICT, "생성 가능한 좌석 번호가 부족합니다.");
        }
        continue;
      }

      try {
        Seat seat = seatRepository.save(Seat.builder()
            .seatSection(seatSection)
            .seatRow(seatRow)
            .seatNumber(seatNumber)
            .build());
        created.add(SeatSummary.from(seat));
        createdCount++;
      } catch (RuntimeException ex) {
        skipped++;
      } finally {
        cursor++;
      }
    }

    seatAuditLogRepository.save(SeatAuditLog.builder()
        .venue(seatSection.getVenue())
        .action("AUTO_GENERATE_PHYSICAL_SEAT")
        .actor(actor == null || actor.isBlank() ? "unknown" : actor)
        .detail(String.format("seatSectionId=%d,requested=%d,created=%d,skipped=%d",
            seatSectionId, request.getCount(), createdCount, skipped))
        .build());

    return PhysicalSeatBulkCreateResponse.builder()
        .seatSectionId(seatSectionId)
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
