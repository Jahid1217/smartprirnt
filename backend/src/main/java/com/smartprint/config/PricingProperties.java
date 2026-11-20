package com.smartprint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Per-page print rates used for server-side pricing. Amounts are in the shop currency.
 */
@ConfigurationProperties(prefix = "smartprint.pricing")
public record PricingProperties(
    BigDecimal bwPerPage,
    BigDecimal colorPerPage
) {
}
