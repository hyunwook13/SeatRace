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
@Table(name = "events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "venue_id", nullable = false)
  private Venue venue;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(nullable = false)
  private LocalDateTime startAt;

  @Column(nullable = false)
  private LocalDateTime endAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private EventStatus status;

  @Builder
  public Event(Venue venue, String name, LocalDateTime startAt, LocalDateTime endAt,
      EventStatus status) {
    this.venue = venue;
    this.name = name;
    this.startAt = startAt;
    this.endAt = endAt;
    this.status = status;
  }
}
