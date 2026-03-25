package org.example.seatrace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "reservation_seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationSeat extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "reservation_id", nullable = false)
  private Reservation reservation;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "event_seat_id", nullable = false)
  private EventSeat eventSeat;

  @Column(nullable = false)
  private boolean active;

  @Builder
  private ReservationSeat(Reservation reservation, EventSeat eventSeat, boolean active) {
    this.reservation = reservation;
    this.eventSeat = eventSeat;
    this.active = active;
  }

  public static ReservationSeat hold(Reservation reservation, EventSeat eventSeat) {
    return ReservationSeat.builder()
        .reservation(reservation)
        .eventSeat(eventSeat)
        .active(true)
        .build();
  }

  public void deactivate() {
    this.active = false;
  }
}
