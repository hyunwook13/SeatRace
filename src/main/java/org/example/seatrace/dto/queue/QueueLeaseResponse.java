package org.example.seatrace.dto.queue;

public record QueueLeaseResponse(
    String admissionToken,
    long admissionExpiresAtMillis
) {
}
