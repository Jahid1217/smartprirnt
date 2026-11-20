package com.smartprint.payment;

import com.smartprint.order.OrderDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/orders/{publicToken}/payments")
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/reference")
    public OrderDtos.PublicOrder submitReference(@PathVariable UUID publicToken,
                                                 @Valid @RequestBody PaymentReferenceRequest request) {
        return paymentService.submitReference(publicToken, request.transactionId());
    }
}
