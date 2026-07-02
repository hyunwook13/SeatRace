package org.example.seatrace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "reservations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "event_id", nullable = false)
  private Event event;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ReservationStatus status;

  @Column
  private LocalDateTime expiresAt;

  @Column(length = 100, unique = true)
  private String paymentOrderId;

  @Column(length = 200)
  private String paymentKey;

  @Column
  private Long paymentAmount;

  @Enumerated(EnumType.STRING)
  @Column(length = 20)
  private PaymentStatus paymentStatus;

  @Column(length = 500)
  private String paymentFailureReason;

  @Column
  private LocalDateTime paidAt;

  @Builder
  public Reservation(User user, Event event, ReservationStatus status,
      LocalDateTime expiresAt) {
    this.user = user;
    this.event = event;
    this.status = status;
    this.expiresAt = expiresAt;
  }

  public void expire() {
    this.status = ReservationStatus.EXPIRED;
  }

  public void confirm() {
    this.status = ReservationStatus.CONFIRMED;
  }

  public void cancel() {
    this.status = ReservationStatus.CANCELLED;
  }

  public void issuePaymentOrder(String paymentOrderId, Long paymentAmount) {
    this.paymentOrderId = paymentOrderId;
    this.paymentAmount = paymentAmount;
    this.paymentStatus = PaymentStatus.PENDING;
    this.paymentFailureReason = null;
  }

  public void confirmPayment(String paymentKey) {
    this.paymentKey = paymentKey;
    this.paymentStatus = PaymentStatus.CONFIRMED;
    this.paymentFailureReason = null;
    this.paidAt = LocalDateTime.now();
  }

  public void failPayment(String reason) {
    this.paymentStatus = PaymentStatus.FAILED;
    this.paymentFailureReason = reason;
  }
}
