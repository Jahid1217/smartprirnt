package com.smartprint.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${smartprint.auth.jwt-secret:}") String configuredSecret,
                      @Value("${smartprint.auth.token-ttl-hours:12}") long ttlHours) {
        String material = configuredSecret;
        if (material == null || material.isBlank()) {
            material = UUID.randomUUID().toString();
            log.warn("JWT_SECRET is not set; using an ephemeral secret. Admin sessions end on restart.");
        }
        this.key = Keys.hmacShaKeyFor(sha256(material));
        this.ttl = Duration.ofHours(Math.max(1, ttlHours));
    }

    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(user.getId()))
            .claim("shopId", user.getShopId())
            .claim("username", user.getUsername())
            .claim("name", user.getFullName())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key)
            .compact();
    }

    public AdminPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
            Number shopId = claims.get("shopId", Number.class);
            if (shopId == null) throw new JwtException("Token has no shop");
            return new AdminPrincipal(
                Long.valueOf(claims.getSubject()),
                shopId.longValue(),
                claims.get("username", String.class),
                claims.get("name", String.class));
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException("Your session is invalid or has expired");
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) { super(message); }
    }
}
