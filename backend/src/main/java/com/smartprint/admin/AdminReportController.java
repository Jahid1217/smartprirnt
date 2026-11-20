package com.smartprint.admin;

import com.smartprint.auth.AdminPrincipal;
import com.smartprint.order.OrderStatus;
import com.smartprint.order.OrderRepository;
import com.smartprint.payment.PaymentRepository;
import com.smartprint.shop.Shop;
import com.smartprint.shop.ShopService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminReportController {
    public record SalesReport(
        LocalDate from,
        LocalDate to,
        String currency,
        long orders,
        long completed,
        long cancelled,
        BigDecimal revenue
    ) {}

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ShopService shopService;

    public AdminReportController(OrderRepository orderRepository,
                                 PaymentRepository paymentRepository,
                                 ShopService shopService) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.shopService = shopService;
    }

    @GetMapping("/reports/sales")
    public SalesReport sales(@AuthenticationPrincipal AdminPrincipal principal,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to) {
        Shop shop = shopService.requireById(principal.shopId());
        ZoneId zone = ZoneId.of(shop.getTimezone());
        LocalDate today = LocalDate.now(zone);
        LocalDate startDate = parseOr(from, today.minusDays(6));
        LocalDate endDate = parseOr(to, today);
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("The start date must be before the end date");
        }
        Instant start = startDate.atStartOfDay(zone).toInstant();
        Instant end = endDate.plusDays(1).atStartOfDay(zone).toInstant();

        return new SalesReport(
            startDate, endDate, shop.getCurrency(),
            orderRepository.countByShopIdAndCreatedAtBetween(shop.getId(), start, end),
            orderRepository.countByShopIdAndStatusAndCreatedAtBetween(
                shop.getId(), OrderStatus.COMPLETED, start, end),
            orderRepository.countByShopIdAndStatusAndCreatedAtBetween(
                shop.getId(), OrderStatus.CANCELLED, start, end),
            paymentRepository.sumApprovedForShopBetween(shop.getId(), start, end));
    }

    private LocalDate parseOr(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Dates must use the YYYY-MM-DD format");
        }
    }
}
