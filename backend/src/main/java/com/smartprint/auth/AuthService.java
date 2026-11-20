package com.smartprint.auth;

import com.smartprint.common.NotFoundException;
import com.smartprint.shop.Shop;
import com.smartprint.shop.ShopRepository;
import com.smartprint.shop.ShopService;
import com.smartprint.shop.ShopSettings;
import com.smartprint.shop.ShopSettingsRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final ShopRepository shopRepository;
    private final ShopSettingsRepository settingsRepository;
    private final ShopService shopService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(ShopRepository shopRepository,
                       ShopSettingsRepository settingsRepository,
                       ShopService shopService,
                       UserRepository userRepository,
                       RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.shopRepository = shopRepository;
        this.settingsRepository = settingsRepository;
        this.shopService = shopService;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterShopRequest request) {
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must be 72 UTF-8 bytes or fewer");
        }
        if (!username.matches("[a-z0-9_.-]{3,60}")) {
            throw new IllegalArgumentException(
                "Username may contain lower-case letters, digits, dots, dashes and underscores");
        }
        if (userRepository.existsByUsername(username)) {
            throw new IllegalStateException("That username is already taken");
        }
        if (request.email() != null && userRepository.existsByEmail(request.email().trim())) {
            throw new IllegalStateException("That email is already registered");
        }

        Shop shop = shopRepository.save(new Shop(
            uniqueShopCode(request.shopName()),
            request.shopName().trim(),
            request.ownerName().trim(),
            request.phone().trim(),
            request.email().trim(),
            request.address() == null ? null : request.address().trim(),
            "Asia/Dhaka",
            "BDT"));
        settingsRepository.save(ShopSettings.defaults(shop.getId()));

        Role role = roleRepository.findByName("ADMIN")
            .orElseGet(() -> roleRepository.save(new Role("ADMIN")));
        User user = userRepository.save(new User(
            shop.getId(), role.getId(), request.ownerName().trim(), request.email().trim(),
            username, passwordEncoder.encode(request.password()), request.phone().trim()));

        return response(shop, user);
    }

    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new InvalidCredentialsException("Incorrect username or password"));
        if (!"ACTIVE".equals(user.getStatus())
            || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Incorrect username or password");
        }
        Shop shop = shopService.requireById(user.getShopId());
        return response(shop, user);
    }

    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse current(AdminPrincipal principal) {
        User user = userRepository.findById(principal.userId())
            .orElseThrow(() -> new NotFoundException("Admin account no longer exists"));
        return response(shopService.requireById(principal.shopId()), user);
    }

    private AuthDtos.AuthResponse response(Shop shop, User user) {
        return new AuthDtos.AuthResponse(
            jwtService.issue(user),
            shop.getShopCode(),
            shop.getName(),
            shop.getQrToken().toString(),
            user.getId(),
            user.getFullName(),
            user.getUsername());
    }

    private String uniqueShopCode(String shopName) {
        String base = shopName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (base.length() > 6) base = base.substring(0, 6);
        if (base.isEmpty()) base = "SHOP";
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = base + randomToken(4);
            if (!shopRepository.existsByShopCode(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not allocate a shop code; try again");
    }

    private String randomToken(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
