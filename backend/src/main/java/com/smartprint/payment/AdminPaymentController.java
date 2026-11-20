package com.smartprint.payment;

import com.smartprint.auth.AdminPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/payments")
public class AdminPaymentController {
    public record RejectRequest(String reason) {}

    private final PaymentService paymentService;

    public AdminPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/pending")
    public List<AdminPaymentResponse> pending(@AuthenticationPrincipal AdminPrincipal principal) {
        return paymentService.pendingPayments(principal.shopId());
    }

    @PostMapping("/{paymentId}/approve")
    public PaymentReviewResponse approve(@AuthenticationPrincipal AdminPrincipal principal,
                                         @PathVariable Long paymentId) {
        return paymentService.approve(principal.shopId(), paymentId);
    }

    @PostMapping("/{paymentId}/reject")
    public PaymentReviewResponse reject(@AuthenticationPrincipal AdminPrincipal principal,
                                        @PathVariable Long paymentId,
                                        @RequestBody(required = false) RejectRequest request) {
        return paymentService.reject(principal.shopId(), paymentId,
            request == null ? null : request.reason());
    }
}
