package com.smartprint.payment;

import com.smartprint.common.NotFoundException;
import com.smartprint.order.Order;
import com.smartprint.order.OrderDtos;
import com.smartprint.order.OrderRepository;
import com.smartprint.order.OrderService;
import com.smartprint.order.OrderStatus;
import com.smartprint.queue.QueueEntry;
import com.smartprint.queue.QueueService;
import com.smartprint.realtime.RealtimeService;
import com.smartprint.shop.Shop;
import com.smartprint.shop.ShopService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final QueueService queueService;
    private final ShopService shopService;
    private final RealtimeService realtime;

    public PaymentService(PaymentRepository paymentRepository,
                          OrderRepository orderRepository,
                          OrderService orderService,
                          QueueService queueService,
                          ShopService shopService,
                          RealtimeService realtime) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.queueService = queueService;
        this.shopService = shopService;
        this.realtime = realtime;
    }

    @Transactional
    public OrderDtos.PublicOrder submitReference(UUID publicToken, String transactionId) {
        Order order = orderRepository.findByPublicToken(publicToken)
            .orElseThrow(() -> new NotFoundException("This order link is no longer valid"));
        Payment payment = requirePayment(order.getId());

        if (payment.getPaymentMethod() == PaymentMethod.CASH) {
            throw new IllegalArgumentException("Cash orders are paid at the counter; no transaction ID is needed");
        }
        String reference = transactionId.trim().toUpperCase(Locale.ROOT);
        if (payment.getStatus() == PaymentStatus.SUBMITTED) {
            if (reference.equals(payment.getTransactionId())) {
                return orderService.publicOrder(publicToken);
            }
            throw new IllegalStateException("A different transaction ID was already submitted");
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new IllegalStateException("Payment is " + payment.getStatus() + " and cannot be submitted");
        }
        if (paymentRepository.existsByProviderAndTransactionId(payment.getProvider(), reference)) {
            throw new IllegalStateException("That transaction ID has already been used");
        }

        payment.submitReference(reference);
        paymentRepository.save(payment);
        Shop shop = shopService.requireById(order.getShopId());
        OrderDtos.PublicOrder view = orderService.publicOrder(publicToken);
        realtime.orderChanged(publicToken);
        realtime.shopChanged(shop.getId());
        return view;
    }

    @Transactional(readOnly = true)
    public List<AdminPaymentResponse> pendingPayments(Long shopId) {
        Shop shop = shopService.requireById(shopId);
        List<Payment> payments = paymentRepository.findForShopByStatuses(
            shopId, List.of(PaymentStatus.PENDING.name(), PaymentStatus.SUBMITTED.name()));
        if (payments.isEmpty()) return List.of();

        Map<Long, Order> orders = orderRepository.findAllById(
                payments.stream().map(Payment::getOrderId).toList())
            .stream().collect(Collectors.toMap(Order::getId, Function.identity()));

        return payments.stream()
            .map(payment -> {
                Order order = orders.get(payment.getOrderId());
                return new AdminPaymentResponse(
                    payment.getId(),
                    order == null ? "?" : order.getOrderNumber(),
                    payment.getPaymentMethod().name(),
                    payment.getStatus().name(),
                    payment.getAmount(),
                    order == null ? shop.getCurrency() : order.getCurrency(),
                    payment.getTransactionId(),
                    payment.getCreatedAt());
            })
            .toList();
    }

    @Transactional
    public PaymentReviewResponse approve(Long shopId, Long paymentId) {
        Payment payment = requireShopPayment(shopId, paymentId);
        queueService.lockShop(shopId);
        Order order = requireOrderById(payment.getOrderId());

        if (payment.getStatus() == PaymentStatus.REJECTED) {
            throw new IllegalStateException("A rejected payment cannot be approved");
        }
        if (payment.getStatus() == PaymentStatus.APPROVED) {
            return review(payment, order);
        }
        if (payment.getPaymentMethod() != PaymentMethod.CASH
            && (payment.getTransactionId() == null || payment.getTransactionId().isBlank())) {
            throw new IllegalStateException(
                "A transaction ID must be submitted before this payment can be approved");
        }

        if (order.getPurgeAt() == null || !order.getPurgeAt().isAfter(Instant.now())) {
            throw new IllegalStateException("The order's source files have expired");
        }

        payment.approve();
        paymentRepository.save(payment);

        order.changeStatus(OrderStatus.PAYMENT_APPROVED);
        orderRepository.save(order);

        Shop shop = shopService.requireById(order.getShopId());
        QueueEntry queue = queueService.assignAfterPaymentApproval(
            order.getShopId(), order.getId(), shopService.today(shop));

        order.changeStatus(OrderStatus.QUEUED);
        orderRepository.save(order);

        realtime.orderChanged(order.getPublicToken());
        realtime.shopChanged(order.getShopId());

        return new PaymentReviewResponse(
            payment.getId(), order.getOrderNumber(), payment.getStatus().name(),
            order.getStatus().name(), queue.getQueueNumber(), queue.getQueueDate());
    }

    @Transactional
    public PaymentReviewResponse reject(Long shopId, Long paymentId, String reason) {
        Payment payment = requireShopPayment(shopId, paymentId);
        queueService.lockShop(shopId);
        Order order = requireOrderById(payment.getOrderId());

        if (payment.getStatus() == PaymentStatus.APPROVED) {
            throw new IllegalStateException("An approved payment cannot be rejected");
        }
        if (payment.getStatus() == PaymentStatus.REJECTED) {
            return review(payment, order);
        }

        String rejection = reason == null || reason.isBlank()
            ? "Rejected by the shop" : reason.trim();
        payment.reject(rejection);
        paymentRepository.save(payment);

        order.changeStatus(OrderStatus.PAYMENT_REJECTED);
        orderRepository.save(order);

        realtime.orderChanged(order.getPublicToken());
        realtime.shopChanged(order.getShopId());

        return review(payment, order);
    }

    private PaymentReviewResponse review(Payment payment, Order order) {
        QueueEntry queue = null;
        if (order.getStatus() == OrderStatus.QUEUED) {
            queue = queueService.queueFor(order.getId());
        }
        return new PaymentReviewResponse(
            payment.getId(), order.getOrderNumber(), payment.getStatus().name(),
            order.getStatus().name(),
            queue == null ? null : queue.getQueueNumber(),
            queue == null ? null : queue.getQueueDate());
    }

    private Payment requireShopPayment(Long shopId, Long paymentId) {
        Payment payment = paymentRepository.lockById(paymentId)
            .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " was not found"));
        if (!orderRepository.findShopId(payment.getOrderId()).filter(shopId::equals).isPresent()) {
            throw new NotFoundException("Payment " + paymentId + " was not found");
        }
        return payment;
    }

    private Order requireOrderById(Long orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new NotFoundException("Order " + orderId + " was not found"));
    }

    private Payment requirePayment(Long orderId) {
        return paymentRepository.lockByOrderId(orderId)
            .orElseThrow(() -> new IllegalStateException("Order has no payment record"));
    }
}
