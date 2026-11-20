package com.smartprint.printer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "printers")
public class Printer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false)
    private Long shopId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "printer_code", nullable = false)
    private String printerCode;

    @Column(nullable = false, length = 512)
    private String name;

    @Column(name = "connection_type", nullable = false)
    private String connectionType;

    @Column(name = "printer_uri", nullable = false)
    private String printerUri;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "color_supported", nullable = false)
    private boolean colorSupported;

    @Column(name = "duplex_supported", nullable = false)
    private boolean duplexSupported;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "agent_default", nullable = false)
    private boolean agentDefault;

    public boolean isAgentDefault() { return agentDefault; }
    public void setAgentDefault(boolean value) { agentDefault = value; }

    @Column(name = "virtual_printer", nullable = false)
    private boolean virtualPrinter;

    @Column(name = "device_group", length = 64)
    private String deviceGroup;

    public boolean isVirtualPrinter() { return virtualPrinter; }
    public String getDeviceGroup() { return deviceGroup; }
    public void setDeviceMetadata(boolean virtualPrinter, String deviceGroup) {
        this.virtualPrinter = virtualPrinter;
        this.deviceGroup = deviceGroup;
    }

    @Column(length = 200)
    private String model;

    @Column(name = "pending_jobs", nullable = false)
    private int pendingJobs;

    @Column(name = "active_jobs", nullable = false)
    private int activeJobs;

    @Column(name = "last_assigned_at")
    private Instant lastAssignedAt;

    public String getModel() { return model; }
    public int getPendingJobs() { return pendingJobs; }
    public int getActiveJobs() { return activeJobs; }
    public Instant getLastAssignedAt() { return lastAssignedAt; }
    public void markAssigned(Instant at) { lastAssignedAt = at; }

    public void reportWorkload(String model, int pendingJobs, int activeJobs) {
        if (model != null) this.model = model;
        this.pendingJobs = pendingJobs;
        this.activeJobs = activeJobs;
    }

    @Column(name = "port_name", length = 512)
    private String portName;

    public String getPortName() { return portName; }
    public void setPortName(String value) { if (value != null) portName = value; }

    protected Printer() {}

    public Printer(Long shopId, Long agentId, String printerCode, String name,
                   String connectionType, String printerUri) {
        this.shopId = shopId;
        this.agentId = agentId;
        this.printerCode = printerCode;
        this.name = name;
        this.connectionType = connectionType;
        this.printerUri = printerUri;
        this.status = "ONLINE";
        this.enabled = true;
        this.lastSeenAt = Instant.now();
    }

    public void updateFromAgent(Long agentId, String name, String connectionType,
                               String printerUri, String status, boolean colorSupported,
                               boolean duplexSupported) {
        this.agentId = agentId;
        this.name = name;
        this.connectionType = connectionType;
        this.printerUri = printerUri;
        this.status = status;
        this.colorSupported = colorSupported;
        this.duplexSupported = duplexSupported;
        this.lastSeenAt = Instant.now();
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void markOffline() { this.status = "OFFLINE"; }

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public Long getAgentId() { return agentId; }
    public String getPrinterCode() { return printerCode; }
    public String getName() { return name; }
    public String getConnectionType() { return connectionType; }
    public String getPrinterUri() { return printerUri; }
    public String getStatus() { return status; }
    public boolean isEnabled() { return enabled; }
    public boolean isColorSupported() { return colorSupported; }
    public boolean isDuplexSupported() { return duplexSupported; }
    public Instant getLastSeenAt() { return lastSeenAt; }
}
