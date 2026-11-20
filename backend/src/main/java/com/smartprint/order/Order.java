package com.smartprint.order;

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
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false)
    private Long shopId;

    @Column(name = "customer_session_id")
    private java.util.UUID customerSessionId;

    @Column(name = "order_number", nullable = false, unique = true)
    private String orderNumber;

    @Column(name = "request_key")
    private UUID requestKey;

    @Column(name = "public_token", nullable = false, unique = true)
    private UUID publicToken;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "configuration_mode", nullable = false)
    private ConfigurationMode configurationMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "collection_method", nullable = false)
    private CollectionMethod collectionMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "purge_at")
    private Instant purgeAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {}

    public Order(Long shopId, String orderNumber, ConfigurationMode configurationMode,
                 CollectionMethod collectionMethod, BigDecimal totalAmount, String currency) {
        this.shopId = shopId;
        this.orderNumber = orderNumber;
        this.publicToken = UUID.randomUUID();
        this.configurationMode = configurationMode;
        this.collectionMethod = collectionMethod;
        this.status = OrderStatus.PAYMENT_PENDING;
        this.totalAmount = totalAmount;
        this.currency = currency;
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

    public void changeStatus(OrderStatus status) {
        this.status = status;
    }

    public void setRequestKey(UUID requestKey) {
        this.requestKey = requestKey;
    }

    public void setCustomer(String name, String phone) {
        this.customerName = name;
        this.customerPhone = phone;
    }

    public void schedulePurge(Instant purgeAt) {
        this.purgeAt = purgeAt;
    }

    public void clearPurge() {
        this.purgeAt = null;
    }

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public String getOrderNumber() { return orderNumber; }
    public UUID getPublicToken() { return publicToken; }
    public String getCustomerName() { return customerName; }
    public String getCustomerPhone() { return customerPhone; }
    public ConfigurationMode getConfigurationMode() { return configurationMode; }
    public CollectionMethod getCollectionMethod() { return collectionMethod; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public Instant getPurgeAt() { return purgeAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
