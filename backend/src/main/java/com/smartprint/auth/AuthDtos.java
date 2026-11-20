package com.smartprint.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterShopRequest(
        @NotBlank @Size(max = 150) String shopName,
        @NotBlank @Size(max = 150) String ownerName,
        @NotBlank @Size(max = 30) String phone,
        @NotBlank @Email @Size(max = 150) String email,
        @Size(max = 400) String address,
        @NotBlank @Size(min = 3, max = 60) String username,
        @NotBlank @Size(min = 8, max = 100) String password
    ) {}

    public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
    ) {}

    public record AuthResponse(
        String token,
        String shopCode,
        String shopName,
        String qrToken,
        Long adminId,
        String adminName,
        String username
    ) {}
}
