package com.smartprint.order;

import com.smartprint.auth.AdminPrincipal;
import com.smartprint.realtime.RealtimeService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@Validated
public class AdminOrderController {
    private final OrderService orderService;
    private final RealtimeService realtime;

    public AdminOrderController(OrderService orderService, RealtimeService realtime) {
        this.orderService = orderService;
        this.realtime = realtime;
    }

    @GetMapping("/orders")
    public List<OrderDtos.AdminOrder> orders(
            @AuthenticationPrincipal AdminPrincipal principal,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page) {
        return orderService.adminOrders(principal.shopId(), status, limit, page);
    }

    @GetMapping("/orders/{orderNumber}")
    public OrderDtos.AdminOrder order(@AuthenticationPrincipal AdminPrincipal principal,
                                      @PathVariable String orderNumber) {
        return orderService.adminOrder(principal.shopId(), orderNumber);
    }

    @GetMapping(value = "/orders/{orderNumber}/files/{fileId}/preview.pdf",
        produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> preview(@AuthenticationPrincipal AdminPrincipal principal,
                                          @PathVariable String orderNumber,
                                          @PathVariable Long fileId) {
        byte[] pdf = orderService.adminPreview(principal.shopId(), orderNumber, fileId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf);
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal AdminPrincipal principal) {
        return realtime.subscribeShop(principal.shopId());
    }
}
