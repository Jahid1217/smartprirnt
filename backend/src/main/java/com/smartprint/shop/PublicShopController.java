package com.smartprint.shop;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/shops")
public class PublicShopController {
    private final ShopService shopService;
    private final ShopSettingsService settingsService;
    private final QrCodeService qrCodeService;

    public PublicShopController(ShopService shopService,
                                ShopSettingsService settingsService,
                                QrCodeService qrCodeService) {
        this.shopService = shopService;
        this.settingsService = settingsService;
        this.qrCodeService = qrCodeService;
    }

    @GetMapping("/{qrToken}")
    public ShopDtos.PublicShop shop(@PathVariable UUID qrToken) {
        Shop shop = shopService.requireByQrToken(qrToken);
        return settingsService.publicView(shop);
    }

    @GetMapping(value = "/{qrToken}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@PathVariable UUID qrToken) {
        Shop shop = shopService.requireByQrToken(qrToken);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.IMAGE_PNG)
            .body(qrCodeService.png(shop));
    }
}
