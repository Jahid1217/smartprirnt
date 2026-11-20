package com.smartprint.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record OrderResponse(
    String orderNumber,
    String status,
    BigDecimal totalAmount,
    String currency,
    String configurationMode,
    String collectionMethod,
    Instant createdAt,
    PaymentView payment,
    QueueView queue
) {
    public record PaymentView(
        Long id,
        String method,
        String status,
        String provider,
        BigDecimal amount,
        String transactionId,
        String instructions
    ) {}

    public record QueueView(Integer queueNumber, LocalDate queueDate, String status) {}
}
