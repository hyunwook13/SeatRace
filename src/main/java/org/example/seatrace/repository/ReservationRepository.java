package org.example.seatrace.repository;

import java.util.List;
import java.util.Optional;
import org.example.seatrace.entity.Reservation;
import org.example.seatrace.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

  List<Reservation> findAllByStatus(ReservationStatus status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<Reservation> findByIdAndUser_Id(Long id, Long userId);
}
