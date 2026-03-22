package org.example.seatrace.repository;

import java.util.List;
import java.util.Optional;
import org.example.seatrace.dto.seat.EventSeatStats;
import org.example.seatrace.entity.Event;
import org.example.seatrace.entity.EventSeat;
import org.example.seatrace.entity.EventSeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventSeatRepository extends JpaRepository<EventSeat, Long> {

  List<EventSeat> findAllByEvent(Event event);

  Optional<EventSeat> findByEventIdAndSeatId(Long eventId, Long seatId);


  @Query("""
        SELECT es
        FROM EventSeat es
        WHERE es.event.id = :eventId
          AND es.seat.id IN :seatIds
    """)
  List<EventSeat> findEventSeats(
      @Param("eventId") Long eventId,
      @Param("seatIds") List<Long> seatIds
  );

  @Query("""
      SELECT new org.example.seatrace.dto.seat.EventSeatStats(
          es.event.id,
          COUNT(es),
      SUM(CASE WHEN es.status = :available THEN 1 ELSE 0 END)
      )
      FROM EventSeat es
      WHERE es.event.id IN :eventIds
      GROUP BY es.event.id
      """)
  List<EventSeatStats> aggregateStatsByEventIds(@Param("eventIds") List<Long> eventIds,
      @Param("available") EventSeatStatus available);
}
