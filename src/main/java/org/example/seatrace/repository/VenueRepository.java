package org.example.seatrace.repository;

import java.util.Optional;
import org.example.seatrace.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, Long> {

  Optional<Venue> findByName(String name);

  Optional<Venue> findByLocation(String location);
}
