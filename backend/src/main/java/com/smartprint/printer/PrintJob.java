package com.smartprint.printer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "print_jobs")
public class PrintJob {
    public static final String PENDING = "PENDING";
    public static final String PRINTING = "PRINTING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false)
    private Long shopId;

    @Column(name = "queue_entry_id", nullable = false)
    private Long queueEntryId;

    @Column(name = "order_file_id", nullable = false)
    private Long orderFileId;

    @Column(name = "printer_id")
    private Long printerId;

    @Column(nullable = false)
    private String status;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "agent_job_id")
    private String agentJobId;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PrintJob() {}

    public PrintJob(Long shopId, Long queueEntryId, Long orderFileId, Long printerId) {
        this.shopId = shopId;
        this.queueEntryId = queueEntryId;
        this.orderFileId = orderFileId;
        this.printerId = printerId;
        this.status = PENDING;
        this.idempotencyKey = UUID.randomUUID();
        this.attemptCount = 0;
        this.createdAt = Instant.now();
    }

    public void claim(Long agentId, Long printerId, String agentJobId) {
        this.status = PRINTING;
        this.agentId = agentId;
        this.printerId = printerId;
        this.agentJobId = agentJobId;
        this.claimedAt = Instant.now();
        this.attemptCount++;
    }

    public void done() {
        this.status = DONE;
        this.completedAt = Instant.now();
        this.failureReason = null;
    }

    public void fail(String reason) {
        this.status = FAILED;
        this.completedAt = Instant.now();
        this.failureReason = reason;
    }

    public void reset() {
        this.status = PENDING;
        this.agentId = null;
        this.claimedAt = null;
        this.completedAt = null;
        this.failureReason = null;
    }

    public void cancel() { this.status = CANCELLED; }

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public Long getQueueEntryId() { return queueEntryId; }
    public Long getOrderFileId() { return orderFileId; }
    public Long getPrinterId() { return printerId; }
    public String getStatus() { return status; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public String getAgentJobId() { return agentJobId; }
    public Integer getAttemptCount() { return attemptCount; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getAgentId() { return agentId; }
    public Instant getClaimedAt() { return claimedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
