package org.example.seatrace.service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.seatrace.dto.reservation.HoldSeatRequest;
import org.example.seatrace.dto.reservation.HoldSeatResponse;
import org.example.seatrace.entity.Event;
import org.example.seatrace.entity.EventSeat;
import org.example.seatrace.entity.Reservation;
import org.example.seatrace.entity.ReservationSeat;
import org.example.seatrace.entity.ReservationStatus;
import org.example.seatrace.entity.User;
import org.example.seatrace.repository.EventRepository;
import org.example.seatrace.repository.EventSeatRepository;
import org.example.seatrace.repository.ReservationRepository;
import org.example.seatrace.repository.ReservationSeatRepository;
import org.example.seatrace.repository.UserRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReservationService {

  private final ReservationSeatRepository reservationSeatRepository;
  private final ReservationRepository reservationRepository;
  private final UserRepository userRepository;
  private final EventRepository eventRepository;
  private final EventSeatRepository eventSeatRepository;
  private final ReservationHoldRedisService reservationHoldRedisService;

  @Transactional
  public HoldSeatResponse holdSeats(Long userId, Long eventId, HoldSeatRequest request) {

    List<Long> seatIds = request.getSeatIds();
    LocalDateTime expiresAt = LocalDateTime.now().plus(reservationHoldRedisService.holdTtl());
    log.info("hold 요청 시작: userId={}, eventId={}, requestedSeatIds={}", userId, eventId, seatIds);

    // 1. 좌석 중복 체크
    validateDuplicatedSeatIds(seatIds);

    // 2. 이미 점유된 좌석 있는지 확인
    List<ReservationSeat> alreadyTaken =
        reservationSeatRepository.findActiveSeats(eventId, seatIds);

    releaseMissingRedisHolds(alreadyTaken);
    alreadyTaken = reservationSeatRepository.findActiveSeats(eventId, seatIds);

    log.info("활성 예약 좌석 조회 결과: eventId={}, requestedSeatIds={}, matchedCount={}, matchedEventSeatIds={}",
        eventId,
        seatIds,
        alreadyTaken.size(),
        alreadyTaken.stream().map(rs -> rs.getEventSeat().getId()).toList());

    if (!alreadyTaken.isEmpty()) {
      throw new IllegalStateException("이미 선택된 좌석이 있습니다.");
    }

    User user = userRepository.getReferenceById(userId);
    Event event = eventRepository.getReferenceById(eventId);

    List<EventSeat> seats = eventSeatRepository.findEventSeats(eventId, seatIds);
    log.info("이벤트 좌석 조회 결과: eventId={}, requestedSeatIds={}, foundCount={}, foundSeatIds={}, foundEventSeatIds={}",
        eventId,
        seatIds,
        seats.size(),
        seats.stream().map(es -> es.getSeat().getId()).toList(),
        seats.stream().map(EventSeat::getId).toList());

    if (seats.size() != seatIds.size()) {
      throw new IllegalArgumentException("요청한 좌석을 모두 찾지 못했습니다. seatId/eventSeatId 전달값을 확인하세요.");
    }

    seats.forEach(seat -> seat.holdUntil(expiresAt));

    try {
      eventSeatRepository.saveAllAndFlush(seats);
    } catch (ObjectOptimisticLockingFailureException ex) {
      log.warn("낙관적 락 충돌: eventId={}, requestedSeatIds={}", eventId, seatIds, ex);
      throw new IllegalStateException("다른 사용자가 같은 좌석을 먼저 점유했습니다. 다시 시도해주세요.");
    }

    // 3. reservation 생성 (HOLD 상태)
    Reservation reservation = Reservation.builder()
        .user(user)
        .event(event)
        .status(ReservationStatus.HOLD)
        .expiresAt(expiresAt)
        .build();

    reservationRepository.save(reservation);

    // 4. reservation_seat 생성 (핵심)
    List<ReservationSeat> reservationSeats = seats.stream()
        .map(seat -> ReservationSeat.hold(reservation, seat))
        .toList();

    log.info("reservation_seat 저장 시도: reservationId={}, eventId={}, reservationSeatCount={}, eventSeatIds={}",
        reservation.getId(),
        eventId,
        reservationSeats.size(),
        reservationSeats.stream().map(rs -> rs.getEventSeat().getId()).toList());

    reservationSeatRepository.saveAll(reservationSeats);
    registerRedisHoldAfterCommit(reservation.getId(), seats.stream().map(EventSeat::getId).toList());

    // 5. 응답
    return HoldSeatResponse.builder()
        .reservationId(reservation.getId())
        .eventId(eventId)
        .seatIds(seatIds)
        .status(ReservationStatus.HOLD)
        .expiresAt(expiresAt)
        .build();
  }

  @Transactional
  public int expireMissingRedisHolds() {
    List<Reservation> holdReservations = reservationRepository.findAllByStatus(ReservationStatus.HOLD);
    int expiredCount = 0;

    for (Reservation reservation : holdReservations) {
      if (reservationHoldRedisService.isReservationHoldAlive(reservation.getId())) {
        continue;
      }

      expireReservationHold(reservation);
      expiredCount++;
    }

    return expiredCount;
  }

  private void validateDuplicatedSeatIds(List<Long> seatIds) {
    if (seatIds.isEmpty()) {
      throw new IllegalArgumentException("좌석이 비어있습니다.");
    }

    Set<Long> unique = new HashSet<>(seatIds);

    if (unique.size() != seatIds.size()) {
      throw new IllegalArgumentException("중복된 좌석이 포함되어 있습니다.");
    }
  }

  private void releaseMissingRedisHolds(List<ReservationSeat> activeSeats) {
    for (ReservationSeat activeSeat : activeSeats) {
      Long reservationId = activeSeat.getReservation().getId();

      if (reservationHoldRedisService.isReservationHoldAlive(reservationId)) {
        continue;
      }

      expireReservationHold(activeSeat.getReservation());
    }
  }

  private void expireReservationHold(Reservation reservation) {
    if (reservation.getStatus() != ReservationStatus.HOLD) {
      return;
    }

    List<ReservationSeat> activeSeats =
        reservationSeatRepository.findAllByReservationIdAndActiveTrue(reservation.getId());

    activeSeats.forEach(reservationSeat -> {
      reservationSeat.deactivate();
      reservationSeat.getEventSeat().releaseHold();
    });

    reservation.expire();
    reservationHoldRedisService.clearHold(
        reservation.getId(),
        activeSeats.stream().map(reservationSeat -> reservationSeat.getEventSeat().getId()).toList()
    );
  }

  private void registerRedisHoldAfterCommit(Long reservationId, List<Long> eventSeatIds) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        reservationHoldRedisService.registerHold(reservationId, eventSeatIds);
      }
    });
  }
}
