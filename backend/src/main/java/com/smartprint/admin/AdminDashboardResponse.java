package com.smartprint.admin;

import com.smartprint.payment.AdminPaymentResponse;
import com.smartprint.queue.QueueDtos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminDashboardResponse(
    String shopName,
    String currency,
    long todaysOrders,
    long awaitingVerification,
    long queueToday,
    int printingNow,
    int waiting,
    boolean queuePaused,
    BigDecimal todaysRevenue,
    List<RecentOrder> recentOrders,
    List<AdminPaymentResponse> pendingPayments,
    List<QueueDtos.QueueItem> activeQueue
) {
    public record RecentOrder(
        String orderNumber,
        String status,
        BigDecimal totalAmount,
        String currency,
        Integer queueNumber,
        Instant createdAt
    ) {}
}
