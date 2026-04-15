package org.example.seatrace.repository;

import org.example.seatrace.entity.ReservationHoldDeadletter;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationHoldDeadletterRepository
    extends JpaRepository<ReservationHoldDeadletter, Long> {
}
