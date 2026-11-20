package com.smartprint.queue;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "print_queue_entries",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = "order_id"),
           @UniqueConstraint(columnNames = {"shop_id", "queue_date", "queue_number"})
       })
public class QueueEntry {
    public static final String QUEUED = "QUEUED";
    public static final String PRINTING = "PRINTING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String PAUSED = "PAUSED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false)
    private Long shopId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "queue_date", nullable = false)
    private LocalDate queueDate;

    @Column(name = "queue_number", nullable = false)
    private Integer queueNumber;

    @Column(name = "sort_priority")
    private Long sortPriority;

    @Column(nullable = false)
    private String status = QUEUED;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "assignment_token")
    private UUID assignmentToken;

    public UUID getAssignmentToken() { return assignmentToken; }

    protected QueueEntry() {}

    public QueueEntry(Long shopId, Long orderId, LocalDate queueDate, Integer queueNumber) {
        this.shopId = shopId;
        this.orderId = orderId;
        this.queueDate = queueDate;
        this.queueNumber = queueNumber;
    }

    public void moveTo(long priority) { this.sortPriority = priority; }

    public long priority() { return sortPriority == null ? id : sortPriority; }

    public void markPrinting() {
        this.status = PRINTING;
        this.startedAt = Instant.now();
        this.assignmentToken = UUID.randomUUID();
    }

    public void markCompleted() {
        this.status = COMPLETED;
        this.completedAt = Instant.now();
    }

    public void markFailed() {
        this.status = FAILED;
        this.completedAt = Instant.now();
    }

    public void markQueued() {
        this.status = QUEUED;
        this.startedAt = null;
        this.assignmentToken = null;
        this.completedAt = null;
    }

    public void markCancelled() {
        this.status = CANCELLED;
        this.completedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public Long getOrderId() { return orderId; }
    public LocalDate getQueueDate() { return queueDate; }
    public Integer getQueueNumber() { return queueNumber; }
    public String getStatus() { return status; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
