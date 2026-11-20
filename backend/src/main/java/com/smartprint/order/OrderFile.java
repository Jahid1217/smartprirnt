package com.smartprint.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_files")
public class OrderFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "processed_storage_key")
    private String processedStorageKey;

    @Column(name = "processing_status", nullable = false)
    private String processingStatus;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    protected OrderFile() {}

    public OrderFile(Long orderId, String originalName, String storageKey, String mimeType,
                     Long fileSize, Integer pageCount, String checksumSha256) {
        this.orderId = orderId;
        this.originalName = originalName;
        this.storageKey = storageKey;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
        this.pageCount = pageCount;
        this.processingStatus = "READY";
        this.checksumSha256 = checksumSha256;
    }

    public void markProcessed(String processedStorageKey) {
        this.processedStorageKey = processedStorageKey;
        this.processingStatus = "READY";
    }

    public void markPurged() {
        this.processingStatus = "PURGED";
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getOriginalName() { return originalName; }
    public String getStorageKey() { return storageKey; }
    public String getMimeType() { return mimeType; }
    public Long getFileSize() { return fileSize; }
    public Integer getPageCount() { return pageCount; }
    public String getProcessedStorageKey() { return processedStorageKey; }
    public String getProcessingStatus() { return processingStatus; }
    public String getChecksumSha256() { return checksumSha256; }

    public boolean isPurged() {
        return "PURGED".equals(processingStatus);
    }
}
