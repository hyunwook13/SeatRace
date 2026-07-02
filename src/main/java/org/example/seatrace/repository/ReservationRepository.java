package org.example.seatrace.repository;

import java.util.List;
import java.util.Optional;
import org.example.seatrace.entity.Reservation;
import org.example.seatrace.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

  List<Reservation> findAllByStatus(ReservationStatus status);

  Optional<Reservation> findByIdAndUser_Id(Long id, Long userId);

  Optional<Reservation> findByPaymentOrderIdAndUser_Id(String paymentOrderId, Long userId);
}
