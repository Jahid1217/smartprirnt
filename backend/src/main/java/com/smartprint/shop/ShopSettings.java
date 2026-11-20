package com.smartprint.shop;

import com.smartprint.order.PrintColorMode;
import com.smartprint.payment.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-shop feature switches, prices and printer routing. One row per shop.
 * Everything the customer-facing UI is allowed to show is derived from this row.
 */
@Entity
@Table(name = "shop_settings")
public class ShopSettings {
    @Id
    @Column(name = "shop_id")
    private Long shopId;

    @Column(name = "bw_per_page", nullable = false, precision = 10, scale = 2)
    private BigDecimal bwPerPage;

    @Column(name = "color_per_page", nullable = false, precision = 10, scale = 2)
    private BigDecimal colorPerPage;

    @Column(name = "duplex_percent", nullable = false)
    private Integer duplexPercent;

    @Column(name = "color_enabled", nullable = false)
    private boolean colorEnabled;

    @Column(name = "duplex_enabled", nullable = false)
    private boolean duplexEnabled;

    @Column(name = "landscape_enabled", nullable = false)
    private boolean landscapeEnabled;

    @Column(name = "page_selection_enabled", nullable = false)
    private boolean pageSelectionEnabled;

    @Column(name = "pdf_enabled", nullable = false)
    private boolean pdfEnabled;

    @Column(name = "word_enabled", nullable = false)
    private boolean wordEnabled;

    @Column(name = "image_enabled", nullable = false)
    private boolean imageEnabled;

    @Column(name = "cash_enabled", nullable = false)
    private boolean cashEnabled;

    @Column(name = "bkash_enabled", nullable = false)
    private boolean bkashEnabled;

    @Column(name = "nagad_enabled", nullable = false)
    private boolean nagadEnabled;

    @Column(name = "rocket_enabled", nullable = false)
    private boolean rocketEnabled;

    @Column(name = "bkash_number", nullable = false)
    private String bkashNumber;

    @Column(name = "nagad_number", nullable = false)
    private String nagadNumber;

    @Column(name = "rocket_number", nullable = false)
    private String rocketNumber;

    @Column(name = "queue_paused", nullable = false)
    private boolean queuePaused;

    @Column(name = "file_retention_hours", nullable = false)
    private Integer fileRetentionHours;

    @Column(name = "default_printer_id")
    private Long defaultPrinterId;

    @Column(name = "color_printer_id")
    private Long colorPrinterId;

    @Column(name = "bw_printer_id")
    private Long bwPrinterId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopSettings() {}

    public static ShopSettings defaults(Long shopId) {
        ShopSettings settings = new ShopSettings();
        settings.shopId = shopId;
        settings.bwPerPage = new BigDecimal("2.00");
        settings.colorPerPage = new BigDecimal("10.00");
        settings.duplexPercent = 100;
        settings.colorEnabled = true;
        settings.duplexEnabled = true;
        settings.landscapeEnabled = true;
        settings.pageSelectionEnabled = true;
        settings.pdfEnabled = true;
        settings.wordEnabled = true;
        settings.imageEnabled = true;
        settings.cashEnabled = true;
        settings.bkashEnabled = true;
        settings.nagadEnabled = true;
        settings.rocketEnabled = false;
        settings.bkashNumber = "";
        settings.nagadNumber = "";
        settings.rocketNumber = "";
        settings.queuePaused = false;
        settings.fileRetentionHours = 24;
        settings.updatedAt = Instant.now();
        return settings;
    }

    public BigDecimal rateFor(PrintColorMode mode) {
        return mode == PrintColorMode.COLOR ? colorPerPage : bwPerPage;
    }

    public boolean fileTypeEnabled(FileKind kind) {
        return switch (kind) {
            case PDF -> pdfEnabled;
            case WORD -> wordEnabled;
            case IMAGE -> imageEnabled;
        };
    }

    public boolean paymentMethodEnabled(PaymentMethod method) {
        return switch (method) {
            case CASH -> cashEnabled;
            case BKASH -> bkashEnabled;
            case NAGAD -> nagadEnabled;
            case ROCKET -> rocketEnabled;
        };
    }

    public void apply(BigDecimal bwPerPage, BigDecimal colorPerPage, int duplexPercent,
                      boolean colorEnabled, boolean duplexEnabled, boolean landscapeEnabled,
                      boolean pageSelectionEnabled, boolean pdfEnabled, boolean wordEnabled,
                      boolean imageEnabled, boolean cashEnabled, boolean bkashEnabled,
                      boolean nagadEnabled, boolean rocketEnabled, String bkashNumber,
                      String nagadNumber, String rocketNumber, int retentionHours,
                      Long defaultPrinterId, Long colorPrinterId, Long bwPrinterId) {
        this.bwPerPage = bwPerPage;
        this.colorPerPage = colorPerPage;
        this.duplexPercent = duplexPercent;
        this.colorEnabled = colorEnabled;
        this.duplexEnabled = duplexEnabled;
        this.landscapeEnabled = landscapeEnabled;
        this.pageSelectionEnabled = pageSelectionEnabled;
        this.pdfEnabled = pdfEnabled;
        this.wordEnabled = wordEnabled;
        this.imageEnabled = imageEnabled;
        this.cashEnabled = cashEnabled;
        this.bkashEnabled = bkashEnabled;
        this.nagadEnabled = nagadEnabled;
        this.rocketEnabled = rocketEnabled;
        this.bkashNumber = bkashNumber == null ? "" : bkashNumber.trim();
        this.nagadNumber = nagadNumber == null ? "" : nagadNumber.trim();
        this.rocketNumber = rocketNumber == null ? "" : rocketNumber.trim();
        this.fileRetentionHours = retentionHours;
        this.defaultPrinterId = defaultPrinterId;
        this.colorPrinterId = colorPrinterId;
        this.bwPrinterId = bwPrinterId;
        this.updatedAt = Instant.now();
    }

    public void setQueuePaused(boolean queuePaused) {
        this.queuePaused = queuePaused;
        this.updatedAt = Instant.now();
    }

    public Long getShopId() { return shopId; }
    public BigDecimal getBwPerPage() { return bwPerPage; }
    public BigDecimal getColorPerPage() { return colorPerPage; }
    public Integer getDuplexPercent() { return duplexPercent; }
    public boolean isColorEnabled() { return colorEnabled; }
    public boolean isDuplexEnabled() { return duplexEnabled; }
    public boolean isLandscapeEnabled() { return landscapeEnabled; }
    public boolean isPageSelectionEnabled() { return pageSelectionEnabled; }
    public boolean isPdfEnabled() { return pdfEnabled; }
    public boolean isWordEnabled() { return wordEnabled; }
    public boolean isImageEnabled() { return imageEnabled; }
    public boolean isCashEnabled() { return cashEnabled; }
    public boolean isBkashEnabled() { return bkashEnabled; }
    public boolean isNagadEnabled() { return nagadEnabled; }
    public boolean isRocketEnabled() { return rocketEnabled; }
    public String getBkashNumber() { return bkashNumber; }
    public String getNagadNumber() { return nagadNumber; }
    public String getRocketNumber() { return rocketNumber; }
    public boolean isQueuePaused() { return queuePaused; }
    public Integer getFileRetentionHours() { return fileRetentionHours; }
    public void setPrinterRouting(Long defaultPrinterId, Long colorPrinterId, Long bwPrinterId) {
        this.defaultPrinterId = defaultPrinterId;
        this.colorPrinterId = colorPrinterId;
        this.bwPrinterId = bwPrinterId;
    }

    public Long getDefaultPrinterId() { return defaultPrinterId; }
    public Long getColorPrinterId() { return colorPrinterId; }
    public Long getBwPrinterId() { return bwPrinterId; }
    public Instant getUpdatedAt() { return updatedAt; }

    public enum FileKind { PDF, WORD, IMAGE }
}
