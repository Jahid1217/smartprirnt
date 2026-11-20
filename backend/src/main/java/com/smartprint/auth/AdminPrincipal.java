package com.smartprint.auth;

/** Authenticated shop operator, resolved from the JWT. Never trust a shop id from the client. */
public record AdminPrincipal(Long userId, Long shopId, String username, String fullName) {
}
