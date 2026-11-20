package com.smartprint.queue;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "queue_counters",
       uniqueConstraints = @UniqueConstraint(columnNames = {"shop_id", "queue_date"}))
public class QueueCounter {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_id", nullable = false)
    private Long shopId;

    @Column(name = "queue_date", nullable = false)
    private LocalDate queueDate;

    @Column(name = "next_number", nullable = false)
    private Integer nextNumber = 1;

    protected QueueCounter() {}

    public QueueCounter(Long shopId, LocalDate queueDate) {
        this.shopId = shopId;
        this.queueDate = queueDate;
        this.nextNumber = 1;
    }

    public Long getId() { return id; }
    public Long getShopId() { return shopId; }
    public LocalDate getQueueDate() { return queueDate; }
    public Integer getNextNumber() { return nextNumber; }
    public void increment() { this.nextNumber++; }
}
