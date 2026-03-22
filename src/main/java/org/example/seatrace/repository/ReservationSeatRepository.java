package org.example.seatrace.repository;

import java.util.List;
import org.example.seatrace.entity.ReservationSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {

  @Query("""
      SELECT rs
      FROM ReservationSeat rs
      WHERE rs.eventSeat.event.id = :eventId
        AND rs.eventSeat.seat.id IN :seatIds
        AND rs.active = true
      """)
  List<ReservationSeat> findActiveSeats(
      @Param("eventId") Long eventId,
      @Param("seatIds") List<Long> seatIds
  );

  List<ReservationSeat> findAllByReservationIdAndActiveTrue(Long reservationId);
}
