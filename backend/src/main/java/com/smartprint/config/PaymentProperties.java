package com.smartprint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Manual mobile-wallet destinations shown to customers. Leave blank until the
 * shop's merchant numbers are configured; the API then omits the number from the
 * payment instructions instead of showing a placeholder.
 */
@ConfigurationProperties(prefix = "smartprint.payments")
public record PaymentProperties(
    String bkashNumber,
    String nagadNumber
) {
}
