package org.example.seatrace.dto.queue;

import lombok.Builder;

@Builder
public record QueueEnterResponse(
    boolean admitted,
    long position,
    long activeCount,
    long waitCount,
    int tps,
    int activeLimit,
    long estimatedWaitMillis
) {
}
