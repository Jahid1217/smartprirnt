package com.smartprint.shop;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public final class ShopDtos {
    private ShopDtos() {}

    public record PublicShop(
        String qrToken,
        String shopCode,
        String shopName,
        String address,
        String phone,
        String currency,
        String timezone,
        String publicUrl,
        boolean acceptingOrders,
        Prices prices,
        Features features,
        List<PaymentOption> paymentMethods,
        int maxFiles,
        int maxFileSizeMb,
        int fileRetentionHours
    ) {}

    public record Prices(BigDecimal bwPerPage, BigDecimal colorPerPage, int duplexPercent) {}

    public record Features(boolean color, boolean duplex, boolean landscape,
                           boolean pageSelection, boolean pdf, boolean word, boolean image) {}

    public record PaymentOption(String code, String label, String walletNumber,
                                boolean requiresTransactionId) {}

    public record AdminShop(
        Long id,
        String shopCode,
        String name,
        String ownerName,
        String phone,
        String email,
        String address,
        String currency,
        String timezone,
        String qrToken,
        String publicUrl,
        String qrImageUrl
    ) {}

    public record UpdateShopRequest(
        @Size(max = 150) String name,
        @Size(max = 150) String ownerName,
        @Size(max = 30) String phone,
        @Size(max = 150) String email,
        @Size(max = 400) String address
    ) {}

    public record SettingsView(
        BigDecimal bwPerPage,
        BigDecimal colorPerPage,
        int duplexPercent,
        boolean colorEnabled,
        boolean duplexEnabled,
        boolean landscapeEnabled,
        boolean pageSelectionEnabled,
        boolean pdfEnabled,
        boolean wordEnabled,
        boolean imageEnabled,
        boolean cashEnabled,
        boolean bkashEnabled,
        boolean nagadEnabled,
        boolean rocketEnabled,
        String bkashNumber,
        String nagadNumber,
        String rocketNumber,
        int fileRetentionHours,
        boolean queuePaused,
        Long defaultPrinterId,
        Long colorPrinterId,
        Long bwPrinterId
    ) {}

    public record UpdateSettingsRequest(
        @DecimalMin("0.00") BigDecimal bwPerPage,
        @DecimalMin("0.00") BigDecimal colorPerPage,
        @Min(0) @Max(300) Integer duplexPercent,
        boolean colorEnabled,
        boolean duplexEnabled,
        boolean landscapeEnabled,
        boolean pageSelectionEnabled,
        boolean pdfEnabled,
        boolean wordEnabled,
        boolean imageEnabled,
        boolean cashEnabled,
        boolean bkashEnabled,
        boolean nagadEnabled,
        boolean rocketEnabled,
        @Size(max = 30) String bkashNumber,
        @Size(max = 30) String nagadNumber,
        @Size(max = 30) String rocketNumber,
        @Min(1) @Max(720) Integer fileRetentionHours,
        Long defaultPrinterId,
        Long colorPrinterId,
        Long bwPrinterId
    ) {}
}
