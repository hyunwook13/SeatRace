package org.example.seatrace.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.config.TossPaymentProperties;
import org.example.seatrace.dto.payment.PaymentConfirmRequest;
import org.example.seatrace.dto.payment.PaymentConfirmResponse;
import org.example.seatrace.dto.payment.PaymentOrderResponse;
import org.example.seatrace.dto.reservation.ReservationResponse;
import org.example.seatrace.entity.EventSeat;
import org.example.seatrace.entity.PaymentStatus;
import org.example.seatrace.entity.Reservation;
import org.example.seatrace.entity.ReservationSeat;
import org.example.seatrace.entity.ReservationStatus;
import org.example.seatrace.repository.ReservationRepository;
import org.example.seatrace.repository.ReservationSeatRepository;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PaymentService {

  private final ReservationRepository reservationRepository;
  private final ReservationSeatRepository reservationSeatRepository;
  private final ReservationService reservationService;
  private final TossPaymentProperties tossPaymentProperties;

  @Transactional
  public PaymentOrderResponse createPaymentOrder(Long userId, Long reservationId) {
    Reservation reservation = reservationRepository.findByIdAndUser_Id(reservationId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."));

    if (reservation.getStatus() != ReservationStatus.HOLD) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "결제 가능한 예약 상태가 아닙니다.");
    }

    List<ReservationSeat> activeSeats =
        reservationSeatRepository.findAllByReservationIdAndActiveTrue(reservationId);
    if (activeSeats.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "결제할 좌석이 없습니다.");
    }

    long amount = activeSeats.stream()
        .map(ReservationSeat::getEventSeat)
        .mapToLong(EventSeat::getPrice)
        .sum();

    String orderId = reservation.getPaymentOrderId();
    if (!StringUtils.hasText(orderId)) {
      orderId = buildOrderId(reservationId);
      reservation.issuePaymentOrder(orderId, amount);
      reservationRepository.save(reservation);
    } else if (reservation.getPaymentAmount() == null || reservation.getPaymentAmount() != amount) {
      reservation.issuePaymentOrder(orderId, amount);
      reservationRepository.save(reservation);
    }

    return PaymentOrderResponse.builder()
        .reservationId(reservation.getId())
        .orderId(reservation.getPaymentOrderId())
        .amount(reservation.getPaymentAmount())
        .orderName(buildOrderName(reservation))
        .build();
  }

  @Transactional
  public PaymentConfirmResponse confirmPayment(Long userId, PaymentConfirmRequest request) {
    Reservation reservation = reservationRepository.findByPaymentOrderIdAndUser_Id(
            request.getOrderId(), userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제 주문을 찾을 수 없습니다."));

    if (reservation.getPaymentAmount() == null || !reservation.getPaymentAmount().equals(request.getAmount())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "결제 금액이 일치하지 않습니다.");
    }

    if (reservation.getPaymentStatus() == PaymentStatus.CONFIRMED) {
      return PaymentConfirmResponse.builder()
          .orderId(reservation.getPaymentOrderId())
          .paymentKey(reservation.getPaymentKey())
          .amount(reservation.getPaymentAmount())
          .reservation(reservationService.confirmReservation(userId, reservation.getId()))
          .build();
    }

    tossConfirm(request);
    reservation.confirmPayment(request.getPaymentKey());
    reservationRepository.save(reservation);

    ReservationResponse confirmed = reservationService.confirmReservation(userId, reservation.getId());
    return PaymentConfirmResponse.builder()
        .orderId(reservation.getPaymentOrderId())
        .paymentKey(reservation.getPaymentKey())
        .amount(reservation.getPaymentAmount())
        .reservation(confirmed)
        .build();
  }

  @Transactional
  public PaymentConfirmResponse failPayment(Long userId, String orderId, String reason) {
    Reservation reservation = reservationRepository.findByPaymentOrderIdAndUser_Id(orderId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "결제 주문을 찾을 수 없습니다."));

    reservation.failPayment(reason);
    reservationRepository.save(reservation);

    return PaymentConfirmResponse.builder()
        .orderId(reservation.getPaymentOrderId())
        .paymentKey(reservation.getPaymentKey())
        .amount(reservation.getPaymentAmount())
        .reservation(reservationService.cancelReservation(userId, reservation.getId()))
        .build();
  }

  private void tossConfirm(PaymentConfirmRequest request) {
    String secretKey = tossPaymentProperties.getSecretKey();
    if (!StringUtils.hasText(secretKey)) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
          "Toss 결제 시크릿 키가 설정되지 않았습니다.");
    }

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String basicToken = Base64.getEncoder()
        .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
    headers.set(HttpHeaders.AUTHORIZATION, "Basic " + basicToken);

    Map<String, Object> payload = Map.of(
        "paymentKey", request.getPaymentKey(),
        "orderId", request.getOrderId(),
        "amount", request.getAmount()
    );

    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
    RestTemplate restTemplate = new RestTemplate();
    try {
      ResponseEntity<String> response = restTemplate.postForEntity(
          tossPaymentProperties.getConfirmUrl(),
          entity,
          String.class
      );
      if (!response.getStatusCode().is2xxSuccessful()) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Toss 결제 승인에 실패했습니다.");
      }
    } catch (RestClientException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Toss 결제 승인에 실패했습니다.", ex);
    }
  }

  private String buildOrderId(Long reservationId) {
    return "SEAT-" + reservationId + "-" + UUID.randomUUID().toString().replace("-", "");
  }

  private String buildOrderName(Reservation reservation) {
    List<ReservationSeat> activeSeats =
        reservationSeatRepository.findAllByReservationIdAndActiveTrue(reservation.getId());
    return String.format("%s 좌석 %d매",
        reservation.getEvent().getName(),
        activeSeats.size());
  }
}
