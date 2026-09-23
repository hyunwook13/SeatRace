package org.example.seatrace.service;

/** Shared Redis, independent business failure boundaries. */
public enum RedisOperationFeature {
  SEAT_CACHE("seatCache"),
  QUEUE_STATUS("queueStatus"),
  QUEUE_ENTRY("queueEntry"),
  QUEUE_ADVANCE("queueAdvance"),
  QUEUE_LEASE("queueLease"),
  RESERVATION_HOLD("reservationHold"),
  HOLD_STREAM("holdStream"),
  RESERVATION_LOCK("reservationLock");

  private final String resilienceName;

  RedisOperationFeature(String resilienceName) {
    this.resilienceName = resilienceName;
  }

  public String resilienceName() {
    return resilienceName;
  }

  public boolean usesQueueRedisConnection() {
    return this == QUEUE_STATUS || this == QUEUE_ENTRY || this == QUEUE_ADVANCE || this == QUEUE_LEASE;
  }
}
