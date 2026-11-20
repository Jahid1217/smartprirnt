package com.smartprint.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod;

    @Column
    private String provider;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    protected Payment() {}

    public Payment(Long orderId, PaymentMethod method, BigDecimal amount) {
        this.orderId = orderId;
        this.paymentMethod = method;
        this.provider = providerFor(method);
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }

    private static String providerFor(PaymentMethod method) {
        return switch (method) {
            case CASH -> "COUNTER";
            case BKASH -> "BKASH";
            case NAGAD -> "NAGAD";
            case ROCKET -> "ROCKET";
        };
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void submitReference(String transactionId) {
        this.transactionId = transactionId;
        this.status = PaymentStatus.SUBMITTED;
        this.rejectionReason = null;
    }

    public void approve() {
        this.status = PaymentStatus.APPROVED;
        this.verifiedAt = Instant.now();
        this.rejectionReason = null;
    }

    public void reject(String reason) {
        this.status = PaymentStatus.REJECTED;
        this.verifiedAt = Instant.now();
        this.rejectionReason = reason;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public String getProvider() { return provider; }
    public BigDecimal getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
    public String getTransactionId() { return transactionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public String getRejectionReason() { return rejectionReason; }
}
