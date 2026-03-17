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
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
    name = "event_seats",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_event_seats_event_seat", columnNames = {"event_id", "seat_id"})
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventSeat {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "event_id", nullable = false)
  private Event event;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "seat_id", nullable = false)
  private Seat seat;

  @Column(nullable = false, precision = 10, scale = 2)
  private BigDecimal price;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private EventSeatStatus status;

  @Column
  private LocalDateTime heldUntil;

  @Builder
  public EventSeat(Event event, Seat seat, BigDecimal price, EventSeatStatus status,
      LocalDateTime heldUntil) {
    this.event = event;
    this.seat = seat;
    this.price = price;
    this.status = status;
    this.heldUntil = heldUntil;
  }
}
