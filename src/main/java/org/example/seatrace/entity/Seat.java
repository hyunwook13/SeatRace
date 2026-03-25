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
@Table(name = "seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "venue_id", nullable = false)
  private Venue venue;

  @Column(nullable = false, length = 30)
  private String section;

  @Column(nullable = false, length = 10)
  private String rowNo;

  @Column(nullable = false, length = 10)
  private String seatNo;

  @Column(nullable = false, length = 20)
  private String grade;

  @Builder
  public Seat(Venue venue, String section, String rowNo, String seatNo, String grade) {
    this.venue = venue;
    this.section = section;
    this.rowNo = rowNo;
    this.seatNo = seatNo;
    this.grade = grade;
  }
}
