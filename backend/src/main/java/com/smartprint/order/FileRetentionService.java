package com.smartprint.order;

import com.smartprint.storage.ObjectStorageService;
import com.smartprint.queue.QueueEntry;
import com.smartprint.queue.QueueService;
import com.smartprint.realtime.RealtimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Deletes customer documents once the shop's retention window has passed, whether
 * the job was printed, rejected or never paid. Database rows remain for reporting,
 * but the storage keys are purged and marked so they can no longer be downloaded.
 */
@Service
public class FileRetentionService {
    private static final Logger log = LoggerFactory.getLogger(FileRetentionService.class);

    private final OrderRepository orderRepository;
    private final OrderFileRepository orderFileRepository;
    private final ObjectStorageService storage;
    private final QueueService queueService;
    private final RealtimeService realtime;
    private final TransactionTemplate transaction;

    public FileRetentionService(OrderRepository orderRepository,
                                OrderFileRepository orderFileRepository,
                                ObjectStorageService storage, QueueService queueService,
                                RealtimeService realtime, PlatformTransactionManager manager) {
        this.orderRepository = orderRepository;
        this.orderFileRepository = orderFileRepository;
        this.storage = storage;
        this.queueService = queueService;
        this.realtime = realtime;
        this.transaction = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${smartprint.retention.scan-ms:600000}",
        initialDelayString = "${smartprint.retention.initial-delay-ms:60000}")
    public void purgeExpiredDocuments() {
        Instant cutoff = Instant.now();
        List<Long> expired = orderRepository.findDueForPurge(cutoff, PageRequest.of(0, 200));
        for (Long orderId : expired) {
            try {
                transaction.executeWithoutResult(status -> purge(orderId, cutoff));
            } catch (RuntimeException ex) {
                log.warn("Could not purge documents for order {}", orderId, ex);
            }
        }
    }

    private void purge(Long orderId, Instant cutoff) {
        Long shopId = orderRepository.findShopId(orderId).orElse(null);
        if (shopId == null) return;
        queueService.lockShop(shopId);
        Order order = orderRepository.findById(orderId).orElseThrow();
        if (order.getPurgeAt() == null || order.getPurgeAt().isAfter(cutoff)) return;
        for (OrderFile file : orderFileRepository.findByOrderIdOrderByIdAsc(orderId)) {
            if (file.isPurged()) continue;
            delete(file.getStorageKey());
            delete(file.getProcessedStorageKey());
            file.markPurged();
        }
        order.clearPurge();
        if (order.getStatus() == OrderStatus.PAYMENT_PENDING) {
            order.changeStatus(OrderStatus.CANCELLED);
            realtime.shopChanged(shopId);
        } else if (order.getStatus() == OrderStatus.QUEUED) {
            QueueEntry entry = queueService.queueFor(orderId);
            if (entry != null) queueService.markFailed(entry, shopId);
        }
        realtime.orderChanged(order.getPublicToken());
    }

    private void delete(String key) {
        if (key != null && !key.isBlank()) {
            storage.delete(key);
        }
    }
}
