package org.example.seatrace.repository;

import java.util.List;
import org.example.seatrace.dto.EventSeatStats;
import org.example.seatrace.entity.Event;
import org.example.seatrace.entity.EventSeat;
import org.example.seatrace.entity.EventSeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventSeatRepository extends JpaRepository<EventSeat, Long> {

  List<EventSeat> findAllByEvent(Event event);

  @Query("""
      SELECT new org.example.seatrace.dto.EventSeatStats(
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
