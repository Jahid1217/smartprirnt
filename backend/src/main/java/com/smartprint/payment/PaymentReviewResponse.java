package com.smartprint.payment;

import java.time.LocalDate;

public record PaymentReviewResponse(
    Long paymentId,
    String orderNumber,
    String paymentStatus,
    String orderStatus,
    Integer queueNumber,
    LocalDate queueDate
) {
}
