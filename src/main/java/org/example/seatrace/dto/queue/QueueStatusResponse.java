package org.example.seatrace.dto.queue;

import lombok.Builder;

@Builder
public record QueueStatusResponse(
    boolean admitted,
    boolean waiting,
    long position,
    long activeCount,
    long waitCount,
    int tps,
    int activeLimit,
    long estimatedWaitMillis
) {
}
