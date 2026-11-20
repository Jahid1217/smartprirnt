package com.smartprint.payment;

/**
 * Payment methods the backend can settle today. Online gateways are intentionally
 * absent until a provider is contracted and configured.
 */
public enum PaymentMethod {
    CASH, BKASH, NAGAD, ROCKET
}
