package org.example.seatrace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "reservation_hold_deadletters")
@NoArgsConstructor
public class ReservationHoldDeadletter extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, columnDefinition = "text")
  private String reservationIds;

  @Column(nullable = false, length = 20)
  private String source;

  @Column(nullable = false)
  private int chunkSize;

  @Column(length = 100)
  private String errorType;

  @Column(columnDefinition = "text")
  private String errorMessage;

  public ReservationHoldDeadletter(
      String reservationIds,
      String source,
      int chunkSize,
      String errorType,
      String errorMessage
  ) {
    this.reservationIds = reservationIds;
    this.source = source;
    this.chunkSize = chunkSize;
    this.errorType = errorType;
    this.errorMessage = errorMessage;
  }
}
