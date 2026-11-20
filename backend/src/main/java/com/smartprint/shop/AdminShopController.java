package com.smartprint.shop;

import com.smartprint.auth.AdminPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminShopController {
    private final ShopService shopService;
    private final ShopSettingsService settingsService;
    private final QrCodeService qrCodeService;

    public AdminShopController(ShopService shopService,
                               ShopSettingsService settingsService,
                               QrCodeService qrCodeService) {
        this.shopService = shopService;
        this.settingsService = settingsService;
        this.qrCodeService = qrCodeService;
    }

    @GetMapping("/shop")
    public ShopDtos.AdminShop shop(@AuthenticationPrincipal AdminPrincipal principal) {
        return settingsService.adminShop(shopService.requireById(principal.shopId()));
    }

    @PutMapping("/shop")
    public ShopDtos.AdminShop updateShop(@AuthenticationPrincipal AdminPrincipal principal,
                                         @Valid @RequestBody ShopDtos.UpdateShopRequest request) {
        return settingsService.updateShop(shopService.requireById(principal.shopId()), request);
    }

    @GetMapping(value = "/shop/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@AuthenticationPrincipal AdminPrincipal principal) {
        Shop shop = shopService.requireById(principal.shopId());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + shop.getShopCode() + "-qr.png\"")
            .contentType(MediaType.IMAGE_PNG)
            .body(qrCodeService.png(shop));
    }

    @PostMapping("/shop/qr/regenerate")
    public ShopDtos.AdminShop regenerateQr(@AuthenticationPrincipal AdminPrincipal principal) {
        return settingsService.regenerateQr(shopService.requireById(principal.shopId()));
    }

    @GetMapping("/settings")
    public ShopDtos.SettingsView settings(@AuthenticationPrincipal AdminPrincipal principal) {
        return settingsService.adminView(shopService.requireById(principal.shopId()));
    }

    @PutMapping("/settings")
    public ShopDtos.SettingsView updateSettings(@AuthenticationPrincipal AdminPrincipal principal,
                                                @Valid @RequestBody ShopDtos.UpdateSettingsRequest request) {
        Shop shop = shopService.requireById(principal.shopId());
        settingsService.update(shop, request);
        return settingsService.adminView(shop);
    }
}
