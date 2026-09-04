package org.example.seatrace.service;

/** Shared Redis, independent business failure boundaries. */
public enum RedisOperationFeature {
  SEAT_CACHE("seatCache"),
  QUEUE_ADMISSION("queueAdmission"),
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
}
