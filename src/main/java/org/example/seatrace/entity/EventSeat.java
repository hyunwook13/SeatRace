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
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "event_seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventSeat extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "event_id", nullable = false)
  private Event event;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "seat_id", nullable = false)
  private Seat seat;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private EventSeatStatus status;

  @Column
  private LocalDateTime heldUntil;

  @Version
  @Column(nullable = false)
  private Long version;

  @Builder
  public EventSeat(Event event, Seat seat, EventSeatStatus status, LocalDateTime heldUntil) {
    this.event = event;
    this.seat = seat;
    this.status = status;
    this.heldUntil = heldUntil;
  }

  public void holdUntil(LocalDateTime expiresAt) {
    this.status = EventSeatStatus.HOLD;
    this.heldUntil = expiresAt;
  }

  public void releaseHold() {
    this.status = EventSeatStatus.AVAILABLE;
    this.heldUntil = null;
  }
}
