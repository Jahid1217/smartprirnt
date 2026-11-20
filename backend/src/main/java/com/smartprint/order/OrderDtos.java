package com.smartprint.order;

import com.smartprint.shop.ShopDtos;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class OrderDtos {
    private OrderDtos() {}

    public record QuoteFile(
        String name,
        String kind,
        int pages,
        int selectedPages,
        String pageSelection
    ) {}

    public record Quote(
        List<QuoteFile> files,
        int totalPages,
        int totalSelectedPages,
        BigDecimal totalAmount,
        String currency,
        ShopDtos.Prices prices
    ) {}

    public record FileView(
        Long id,
        String name,
        String kind,
        String mimeType,
        int pages,
        int selectedPages,
        String colorMode,
        String sideMode,
        String orientation,
        String pageSelection,
        int copies,
        String previewUrl
    ) {}

    public record PaymentView(
        Long id,
        String method,
        String status,
        String provider,
        BigDecimal amount,
        String transactionId,
        String rejectionReason,
        String instructions
    ) {}

    public record QueueView(
        Long entryId,
        Integer queueNumber,
        LocalDate queueDate,
        String state,
        Integer position,
        Integer ahead
    ) {}

    public record ShopView(String name, String phone, String address, String currency) {}

    public record PublicOrder(
        String orderNumber,
        String publicToken,
        String status,
        String statusLabel,
        BigDecimal totalAmount,
        String currency,
        Instant createdAt,
        Instant updatedAt,
        List<FileView> files,
        PaymentView payment,
        QueueView queue,
        ShopView shop
    ) {}

    public record AdminOrder(
        Long id,
        String orderNumber,
        String publicToken,
        String status,
        String statusLabel,
        String customerName,
        String customerPhone,
        BigDecimal totalAmount,
        String currency,
        Instant createdAt,
        Instant updatedAt,
        Instant purgeAt,
        String printFailure,
        List<FileView> files,
        PaymentView payment,
        QueueView queue
    ) {}
}
