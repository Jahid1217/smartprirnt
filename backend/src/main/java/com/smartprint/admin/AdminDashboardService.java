package com.smartprint.admin;

import com.smartprint.order.Order;
import com.smartprint.order.OrderRepository;
import com.smartprint.payment.AdminPaymentResponse;
import com.smartprint.payment.PaymentRepository;
import com.smartprint.payment.PaymentService;
import com.smartprint.payment.PaymentStatus;
import com.smartprint.queue.QueueDtos;
import com.smartprint.queue.QueueEntry;
import com.smartprint.queue.QueueEntryRepository;
import com.smartprint.queue.QueueService;
import com.smartprint.shop.Shop;
import com.smartprint.shop.ShopService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AdminDashboardService {
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final QueueEntryRepository queueEntryRepository;
    private final QueueService queueService;
    private final ShopService shopService;

    public AdminDashboardService(OrderRepository orderRepository,
                                 PaymentRepository paymentRepository,
                                 PaymentService paymentService,
                                 QueueEntryRepository queueEntryRepository,
                                 QueueService queueService,
                                 ShopService shopService) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.queueEntryRepository = queueEntryRepository;
        this.queueService = queueService;
        this.shopService = shopService;
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse dashboard(Long shopId) {
        Shop shop = shopService.requireById(shopId);
        ZoneId zone = ZoneId.of(shop.getTimezone());
        ZonedDateTime dayStart = LocalDate.now(zone).atStartOfDay(zone);
        Instant start = dayStart.toInstant();
        Instant end = dayStart.plusDays(1).toInstant();

        List<Order> recent = orderRepository.findTop10ByShopIdOrderByCreatedAtDesc(shop.getId());
        Map<Long, Integer> queueNumbers = recent.isEmpty() ? Map.of()
            : queueEntryRepository.findByOrderIdIn(recent.stream().map(Order::getId).toList())
                .stream().collect(Collectors.toMap(QueueEntry::getOrderId, QueueEntry::getQueueNumber));

        List<AdminPaymentResponse> pendingPayments = paymentService.pendingPayments(shopId);
        QueueDtos.QueueSummary summary = queueService.summary(shopId);

        return new AdminDashboardResponse(
            shop.getName(),
            shop.getCurrency(),
            orderRepository.countByShopIdAndCreatedAtGreaterThanEqual(shop.getId(), start),
            paymentRepository.countForShopByStatuses(
                shop.getId(), List.of(PaymentStatus.PENDING.name(), PaymentStatus.SUBMITTED.name())),
            queueEntryRepository.countByShopIdAndQueueDate(shop.getId(), LocalDate.now(zone)),
            summary.printing(),
            summary.waiting(),
            summary.paused(),
            paymentRepository.sumApprovedForShopBetween(shop.getId(), start, end),
            recent.stream()
                .map(order -> new AdminDashboardResponse.RecentOrder(
                    order.getOrderNumber(),
                    order.getStatus().name(),
                    order.getTotalAmount(),
                    order.getCurrency(),
                    queueNumbers.get(order.getId()),
                    order.getCreatedAt()))
                .toList(),
            pendingPayments,
            queueService.activeQueue(shopId));
    }
}
