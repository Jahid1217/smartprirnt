package com.smartprint.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminPaymentResponse(
    Long id,
    String orderNumber,
    String method,
    String status,
    BigDecimal amount,
    String currency,
    String transactionId,
    Instant createdAt
) {
}
