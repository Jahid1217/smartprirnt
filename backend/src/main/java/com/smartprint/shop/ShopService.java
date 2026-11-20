package com.smartprint.shop;

import com.smartprint.common.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@Service
public class ShopService {
    private final ShopRepository shopRepository;

    public ShopService(ShopRepository shopRepository) {
        this.shopRepository = shopRepository;
    }

    public Shop requireById(Long shopId) {
        return shopRepository.findById(shopId)
            .orElseThrow(() -> new NotFoundException("Shop " + shopId + " no longer exists"));
    }

    /** Resolve the shop from the unguessable token embedded in its QR code. */
    public Shop requireByQrToken(UUID qrToken) {
        return shopRepository.findByQrToken(qrToken)
            .orElseThrow(() -> new NotFoundException("This QR code does not belong to an active shop"));
    }

    public LocalDate today(Shop shop) {
        return LocalDate.now(ZoneId.of(shop.getTimezone()));
    }
}
