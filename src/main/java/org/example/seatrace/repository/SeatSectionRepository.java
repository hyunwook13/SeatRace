package org.example.seatrace.repository;

import java.util.List;
import java.util.Optional;
import org.example.seatrace.entity.SeatSection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatSectionRepository extends JpaRepository<SeatSection, Long> {

  List<SeatSection> findAllByVenueId(Long venueId);

  Optional<SeatSection> findByVenueIdAndName(Long venueId, String name);

  boolean existsByVenueIdAndName(Long venueId, String name);
}
