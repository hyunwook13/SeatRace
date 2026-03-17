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
import java.math.BigDecimal;
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

  @Column(nullable = false, precision = 10, scale = 2)
  private BigDecimal totalAmount;

  @Column
  private LocalDateTime expiresAt;

  @Builder
  public Reservation(User user, Event event, ReservationStatus status, BigDecimal totalAmount,
      LocalDateTime expiresAt) {
    this.user = user;
    this.event = event;
    this.status = status;
    this.totalAmount = totalAmount;
    this.expiresAt = expiresAt;
  }
}
