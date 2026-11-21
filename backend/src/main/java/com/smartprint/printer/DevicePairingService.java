package com.smartprint.printer;

import com.smartprint.auth.InvalidCredentialsException;
import com.smartprint.common.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class DevicePairingService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DevicePairingService.class);
    private final JdbcTemplate jdbc;
    private final PrintDispatchService dispatch;
    private final SecureRandom random = new SecureRandom();

    public record Challenge(UUID id, String code, Instant expiresAt) {}
    public record Status(Long agentId, Instant expiresAt) {}
    private record Target(UUID id, long shopId, String name) {}

    public DevicePairingService(JdbcTemplate jdbc, PrintDispatchService dispatch) {
        this.jdbc = jdbc;
        this.dispatch = dispatch;
    }

    @Transactional
    public Challenge issue(long shopId, String name) {
        var id = UUID.randomUUID();
        var bytes = new byte[10];
        random.nextBytes(bytes);
        String code = HexFormat.of().withUpperCase().formatHex(bytes);
        Instant expires = Instant.now().plusSeconds(300);
        jdbc.update("delete from print_agent_pairings where expires_at < now() - interval '1 day'");
        jdbc.update("""
            insert into print_agent_pairings(id, shop_id, code_hash, device_name, expires_at)
            values (?, ?, ?, ?, ?)
            """, id, shopId, hash(code), name.trim(), Timestamp.from(expires));
        log.info("Desktop pairing challenge issued for shop {}", shopId);
        return new Challenge(id, code, expires);
    }

    public Status status(long shopId, UUID id) {
        return jdbc.query("""
            select agent_id, expires_at from print_agent_pairings where id = ? and shop_id = ?
            """, (rs, row) -> new Status(rs.getObject(1, Long.class), rs.getTimestamp(2).toInstant()),
            id, shopId).stream().findFirst().orElseThrow(() -> new NotFoundException("Pairing code not found"));
    }

    @Transactional
    public PrinterDtos.CreatedAgent redeem(String code) {
        var target = jdbc.query("""
            select id, shop_id, device_name from print_agent_pairings
            where code_hash = ? and agent_id is null and expires_at > clock_timestamp()
            for update
            """, (rs, row) -> new Target(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3)),
            hash(code.toUpperCase(Locale.ROOT))).stream().findFirst()
            .orElseThrow(() -> new InvalidCredentialsException("Invalid, expired, or used pairing code"));
        var credentials = dispatch.createAgent(target.shopId(), target.name());
        jdbc.update("""
            update print_agent_pairings set agent_id = (select id from print_agents where agent_code = ?)
            where id = ?
            """, credentials.agentCode(), target.id());
        log.info("Desktop pairing challenge redeemed for shop {}; waiting for first heartbeat", target.shopId());
        return credentials;
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(code.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
