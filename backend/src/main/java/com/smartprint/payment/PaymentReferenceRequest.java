package com.smartprint.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PaymentReferenceRequest(
    @NotBlank(message = "Transaction ID is required")
    @Size(min = 6, max = 64, message = "Transaction ID must be 6 to 64 characters")
    @Pattern(regexp = "^[A-Za-z0-9-]+$",
             message = "Transaction ID may only contain letters, numbers and dashes")
    String transactionId
) {
}
