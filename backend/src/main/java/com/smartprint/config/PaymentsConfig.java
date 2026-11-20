package com.smartprint.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PricingProperties.class, PaymentProperties.class})
public class PaymentsConfig {
}
