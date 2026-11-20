package com.smartprint.printer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "print_agents")
public class PrintAgent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false)
    private Long shopId;

    @Column(name = "agent_code", nullable = false, unique = true)
    private String agentCode;

    @Column(name = "credential_hash", nullable = false)
    private String credentialHash;

    @Column(nullable = false)
    private String status;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_printer_count", nullable = false)
    private Integer lastPrinterCount;

    @Column(name = "discovery_error")
    private String discoveryError;

    public String getDiscoveryError() { return discoveryError; }
    public void setDiscoveryError(String value) { discoveryError = value; }

    @Column(nullable = false, length = 20)
    private String platform = "CUPS";

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    protected PrintAgent() {}

    public PrintAgent(Long shopId, String agentCode, String credentialHash, String name) {
        this.shopId = shopId;
        this.agentCode = agentCode;
        this.credentialHash = credentialHash;
        this.name = name;
        this.status = "OFFLINE";
        this.createdAt = Instant.now();
        this.lastPrinterCount = 0;
    }

    public void seen(int printerCount) {
        this.status = "ONLINE";
        this.lastSeenAt = Instant.now();
        this.lastPrinterCount = printerCount;
    }

    public void offline() { this.status = "OFFLINE"; }

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public String getAgentCode() { return agentCode; }
    public String getCredentialHash() { return credentialHash; }
    public String getStatus() { return status; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
    public Integer getLastPrinterCount() { return lastPrinterCount; }
}
