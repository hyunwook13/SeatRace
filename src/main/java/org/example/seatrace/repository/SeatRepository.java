package org.example.seatrace.repository;

import java.util.List;
import java.util.Optional;
import org.example.seatrace.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

  Optional<Seat> findByVenueIdAndSectionAndRowNoAndSeatNo(Long venueId, String section,
      String rowNo, String seatNo);

  boolean existsByVenueIdAndSectionAndRowNoAndSeatNo(Long venueId, String section, String rowNo,
      String seatNo);

  List<Seat> findAllByVenueId(Long venueId);
}
