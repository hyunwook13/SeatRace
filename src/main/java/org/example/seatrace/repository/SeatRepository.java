package org.example.seatrace.repository;

import java.util.List;
import java.util.Optional;
import org.example.seatrace.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

  Optional<Seat> findBySeatSectionIdAndSeatRowAndSeatNumber(Long seatSectionId, String seatRow,
      Integer seatNumber);

  boolean existsBySeatSectionIdAndSeatRowAndSeatNumber(Long seatSectionId, String seatRow,
      Integer seatNumber);

  List<Seat> findAllBySeatSectionId(Long seatSectionId);
}
