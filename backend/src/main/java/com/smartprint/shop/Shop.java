package com.smartprint.shop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shops")
public class Shop {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_code", nullable = false, unique = true)
    private String shopCode;

    @Column(nullable = false)
    private String name;

    @Column(name = "owner_name")
    private String ownerName;

    @Column
    private String phone;

    @Column
    private String email;

    @Column
    private String address;

    @Column(nullable = false)
    private String timezone;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String status;

    @Column(name = "qr_token", nullable = false)
    private UUID qrToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Shop() {}

    public Shop(String shopCode, String name, String ownerName, String phone, String email,
                String address, String timezone, String currency) {
        this.shopCode = shopCode;
        this.name = name;
        this.ownerName = ownerName;
        this.phone = phone;
        this.email = email;
        this.address = address;
        this.timezone = timezone;
        this.currency = currency;
        this.status = "ACTIVE";
        this.qrToken = UUID.randomUUID();
    }

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = Instant.now();
    }

    public void rotateQrToken() {
        this.qrToken = UUID.randomUUID();
    }

    public void updateProfile(String name, String ownerName, String phone, String email, String address) {
        this.name = name;
        this.ownerName = ownerName;
        this.phone = phone;
        this.email = email;
        this.address = address;
    }

    public Long getId() { return id; }
    public String getShopCode() { return shopCode; }
    public String getName() { return name; }
    public String getOwnerName() { return ownerName; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getAddress() { return address; }
    public String getTimezone() { return timezone; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public UUID getQrToken() { return qrToken; }
    public Instant getCreatedAt() { return createdAt; }
}
