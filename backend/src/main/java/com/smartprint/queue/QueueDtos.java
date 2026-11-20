package com.smartprint.queue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class QueueDtos {
    private QueueDtos() {}

    public record QueueItem(
        Long entryId,
        Integer queueNumber,
        LocalDate queueDate,
        String state,
        Long orderId,
        String orderNumber,
        String customerName,
        BigDecimal totalAmount,
        String currency,
        int fileCount,
        Instant approvedAt,
        Instant startedAt,
        Instant completedAt
    ) {}

    public record PauseRequest(boolean paused) {}

    public record MoveRequest(int direction) {}

    public record QueueSummary(boolean paused, int active, int printing, int waiting) {}
}
