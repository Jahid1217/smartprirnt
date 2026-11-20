package com.smartprint.queue;

import com.smartprint.common.NotFoundException;
import com.smartprint.order.Order;
import com.smartprint.order.OrderFileRepository;
import com.smartprint.order.OrderRepository;
import com.smartprint.order.OrderStatus;
import com.smartprint.printer.PrintJob;
import com.smartprint.printer.PrintJobRepository;
import com.smartprint.realtime.RealtimeService;
import com.smartprint.shop.Shop;
import com.smartprint.shop.ShopRepository;
import com.smartprint.shop.ShopService;
import com.smartprint.shop.ShopSettings;
import com.smartprint.shop.ShopSettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class QueueService {
    private final ShopRepository shopRepository;
    private final QueueCounterRepository counterRepository;
    private final QueueEntryRepository entryRepository;
    private final OrderRepository orderRepository;
    private final OrderFileRepository orderFileRepository;
    private final PrintJobRepository printJobRepository;
    private final ShopService shopService;
    private final ShopSettingsService settingsService;
    private final RealtimeService realtime;

    public QueueService(ShopRepository shopRepository,
                        QueueCounterRepository counterRepository,
                        QueueEntryRepository entryRepository,
                        OrderRepository orderRepository,
                        OrderFileRepository orderFileRepository,
                        PrintJobRepository printJobRepository,
                        ShopService shopService,
                        ShopSettingsService settingsService,
                        RealtimeService realtime) {
        this.shopRepository = shopRepository;
        this.counterRepository = counterRepository;
        this.entryRepository = entryRepository;
        this.orderRepository = orderRepository;
        this.orderFileRepository = orderFileRepository;
        this.printJobRepository = printJobRepository;
        this.shopService = shopService;
        this.settingsService = settingsService;
        this.realtime = realtime;
    }

    @Transactional(readOnly = true)
    public QueueEntry queueFor(Long orderId) {
        return entryRepository.findByOrderId(orderId).orElse(null);
    }

    @Transactional
    public QueueEntry assignAfterPaymentApproval(Long shopId, Long orderId, LocalDate shopDate) {
        lockShop(shopId);
        // Idempotency: an already queued order must keep its original number.
        var existing = entryRepository.findByOrderId(orderId);
        if (existing.isPresent()) return existing.get();

        counterRepository.findByShopIdAndQueueDate(shopId, shopDate)
            .orElseGet(() -> counterRepository.saveAndFlush(
                new QueueCounter(shopId, shopDate)));

        QueueCounter counter = counterRepository.findLocked(shopId, shopDate)
            .orElseThrow(() -> new IllegalStateException("Queue counter unavailable"));

        int assigned = counter.getNextNumber();
        counter.increment();

        return entryRepository.save(
            new QueueEntry(shopId, orderId, shopDate, assigned)
        );
    }

    @Transactional(readOnly = true)
    public List<QueueDtos.QueueItem> activeQueue(Long shopId) {
        return entryRepository.findActiveForShop(shopId)
            .stream()
            .map(entry -> {
                Order order = orderRepository.findById(entry.getOrderId()).orElse(null);
                return new QueueDtos.QueueItem(
                    entry.getId(), entry.getQueueNumber(), entry.getQueueDate(), entry.getStatus(),
                    entry.getOrderId(), order == null ? "?" : order.getOrderNumber(),
                    order == null ? null : order.getCustomerName(),
                    order == null ? null : order.getTotalAmount(),
                    order == null ? null : order.getCurrency(),
                    (int) orderFileRepository.countByOrderId(entry.getOrderId()),
                    entry.getApprovedAt(), entry.getStartedAt(), entry.getCompletedAt());
            })
            .toList();
    }

    @Transactional(readOnly = true)
    public QueueDtos.QueueSummary summary(Long shopId) {
        List<QueueEntry> active = entryRepository.findActiveForShop(shopId);
        Shop shop = shopService.requireById(shopId);
        int printing = (int) active.stream()
            .filter(entry -> QueueEntry.PRINTING.equals(entry.getStatus())).count();
        return new QueueDtos.QueueSummary(
            settingsService.settingsFor(shop).isQueuePaused(),
            active.size(), printing, active.size() - printing);
    }

    @Transactional
    public QueueDtos.QueueSummary setPaused(Long shopId, boolean paused) {
        lockShop(shopId);
        Shop shop = shopService.requireById(shopId);
        settingsService.setQueuePaused(shop, paused);
        realtime.shopChanged(shopId);
        return summary(shopId);
    }

    @Transactional
    public void move(Long shopId, Long entryId, int direction) {
        if (direction != -1 && direction != 1) {
            throw new IllegalArgumentException("Direction must be -1 (up) or 1 (down)");
        }
        lockShop(shopId);
        QueueEntry entry = requireEntry(shopId, entryId);
        if (!QueueEntry.QUEUED.equals(entry.getStatus())) {
            throw new IllegalStateException("Only queued jobs can be reordered");
        }
        List<QueueEntry> waiting = entryRepository.findActiveForShop(shopId).stream()
            .filter(candidate -> QueueEntry.QUEUED.equals(candidate.getStatus()))
            .toList();
        int index = waiting.indexOf(entry);
        int targetIndex = index + direction;
        if (index < 0 || targetIndex < 0 || targetIndex >= waiting.size()) return;

        QueueEntry target = waiting.get(targetIndex);
        long priority = entry.priority();
        entry.moveTo(target.priority());
        target.moveTo(priority);

        publish(entry);
        publish(target);
        realtime.shopChanged(shopId);
    }

    @Transactional
    public void cancel(Long shopId, Long entryId) {
        lockShop(shopId);
        QueueEntry entry = requireEntry(shopId, entryId);
        if (QueueEntry.PRINTING.equals(entry.getStatus())) {
            throw new IllegalStateException("The job is already printing and cannot be cancelled");
        }
        if (!QueueEntry.QUEUED.equals(entry.getStatus())) {
            throw new IllegalStateException("Only waiting jobs can be cancelled");
        }
        Order order = requireOrder(entry.getOrderId());
        entry.markCancelled();
        entryRepository.save(entry);
        order.changeStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        cancelPendingJobs(entry.getId());
        publish(entry);
        realtime.shopChanged(shopId);
    }

    @Transactional
    public void retry(Long shopId, Long entryId) {
        lockShop(shopId);
        QueueEntry entry = requireEntry(shopId, entryId);
        if (!QueueEntry.FAILED.equals(entry.getStatus())) {
            throw new IllegalStateException("Only failed jobs can be retried");
        }
        Order order = requireOrder(entry.getOrderId());
        var files = orderFileRepository.findByOrderIdOrderByIdAsc(order.getId());
        if (files.isEmpty() || files.stream().anyMatch(file -> file.isPurged())) {
            throw new IllegalStateException("One or more source files have expired");
        }
        entry.markQueued();
        entryRepository.save(entry);
        order.changeStatus(OrderStatus.QUEUED);
        orderRepository.save(order);
        for (PrintJob job : printJobRepository.findByQueueEntryId(entry.getId())) {
            if (!PrintJob.DONE.equals(job.getStatus())) {
                job.reset();
                printJobRepository.save(job);
            }
        }
        publish(entry);
        realtime.shopChanged(shopId);
    }

    @Transactional
    public void completeManually(Long shopId, Long entryId) {
        lockShop(shopId);
        QueueEntry entry = requireEntry(shopId, entryId);
        if (QueueEntry.COMPLETED.equals(entry.getStatus())) return;
        if (!QueueEntry.QUEUED.equals(entry.getStatus())) {
            throw new IllegalStateException("Only waiting jobs can be manually completed");
        }
        Order order = requireOrder(entry.getOrderId());
        entry.markCompleted();
        entryRepository.save(entry);
        order.changeStatus(OrderStatus.COMPLETED);
        order.schedulePurge(Instant.now());
        orderRepository.save(order);
        for (PrintJob job : printJobRepository.findByQueueEntryId(entry.getId())) {
            if (!PrintJob.DONE.equals(job.getStatus())) {
                job.done();
                printJobRepository.save(job);
            }
        }
        publish(entry);
        realtime.shopChanged(shopId);
    }

    public void lockShop(Long shopId) {
        shopRepository.lockById(shopId).orElseThrow(() -> new NotFoundException("Shop was not found"));
    }

    @Transactional
    public void markPrinting(QueueEntry entry, Long shopId) {
        Order order = requireOrder(entry.getOrderId());
        entry.markPrinting();
        entryRepository.save(entry);
        order.changeStatus(OrderStatus.PRINTING);
        orderRepository.save(order);
        publish(entry);
        realtime.shopChanged(shopId);
    }

    @Transactional
    public void markCompleted(QueueEntry entry, Long shopId) {
        Order order = requireOrder(entry.getOrderId());
        entry.markCompleted();
        entryRepository.save(entry);
        order.changeStatus(OrderStatus.COMPLETED);
        order.schedulePurge(Instant.now());
        orderRepository.save(order);
        publish(entry);
        realtime.shopChanged(shopId);
    }

    @Transactional
    public void markFailed(QueueEntry entry, Long shopId) {
        Order order = requireOrder(entry.getOrderId());
        entry.markFailed();
        entryRepository.save(entry);
        order.changeStatus(OrderStatus.FAILED);
        orderRepository.save(order);
        publish(entry);
        realtime.shopChanged(shopId);
    }

    private void cancelPendingJobs(Long queueEntryId) {
        for (PrintJob job : printJobRepository.findByQueueEntryId(queueEntryId)) {
            if (PrintJob.PENDING.equals(job.getStatus())) {
                job.cancel();
                printJobRepository.save(job);
            }
        }
    }

    private void publish(QueueEntry entry) {
        orderRepository.findById(entry.getOrderId())
            .ifPresent(order -> realtime.orderChanged(order.getPublicToken()));
    }

    private QueueEntry requireEntry(Long shopId, Long entryId) {
        QueueEntry entry = entryRepository.findById(entryId)
            .orElseThrow(() -> new NotFoundException("Queue entry " + entryId + " was not found"));
        if (!shopId.equals(entry.getShopId())) {
            throw new NotFoundException("Queue entry " + entryId + " was not found");
        }
        return entry;
    }

    private Order requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new NotFoundException("Order " + orderId + " was not found"));
    }
}
