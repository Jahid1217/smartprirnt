package com.smartprint.shop;

import com.smartprint.common.NotFoundException;
import com.smartprint.payment.PaymentMethod;
import com.smartprint.printer.PrinterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class ShopSettingsService {
    public static final int MAX_FILES = 20;

    private final ShopSettingsRepository settingsRepository;
    private final ShopRepository shopRepository;
    private final PrinterRepository printerRepository;
    private final QrCodeService qrCodeService;

    public ShopSettingsService(ShopSettingsRepository settingsRepository,
                               ShopRepository shopRepository,
                               QrCodeService qrCodeService, PrinterRepository printerRepository) {
        this.settingsRepository = settingsRepository;
        this.shopRepository = shopRepository;
        this.qrCodeService = qrCodeService;
        this.printerRepository = printerRepository;
    }

    @Transactional
    public ShopSettings settingsFor(Shop shop) {
        return settingsRepository.findById(shop.getId())
            .orElseGet(() -> settingsRepository.save(ShopSettings.defaults(shop.getId())));
    }

    @Transactional(readOnly = true)
    public ShopDtos.PublicShop publicView(Shop shop) {
        ShopSettings settings = settingsRepository.findById(shop.getId())
            .orElseGet(() -> ShopSettings.defaults(shop.getId()));

        List<ShopDtos.PaymentOption> paymentMethods = new ArrayList<>();
        if (settings.isCashEnabled()) {
            paymentMethods.add(new ShopDtos.PaymentOption("CASH", "Cash at counter", null, false));
        }
        if (settings.isBkashEnabled()) {
            paymentMethods.add(new ShopDtos.PaymentOption(
                "BKASH", "bKash", blankToNull(settings.getBkashNumber()), true));
        }
        if (settings.isNagadEnabled()) {
            paymentMethods.add(new ShopDtos.PaymentOption(
                "NAGAD", "Nagad", blankToNull(settings.getNagadNumber()), true));
        }
        if (settings.isRocketEnabled()) {
            paymentMethods.add(new ShopDtos.PaymentOption(
                "ROCKET", "Rocket", blankToNull(settings.getRocketNumber()), true));
        }

        return new ShopDtos.PublicShop(
            shop.getQrToken().toString(),
            shop.getShopCode(),
            shop.getName(),
            shop.getAddress(),
            shop.getPhone(),
            shop.getCurrency(),
            shop.getTimezone(),
            qrCodeService.publicUrl(shop),
            "ACTIVE".equals(shop.getStatus()),
            new ShopDtos.Prices(settings.getBwPerPage(), settings.getColorPerPage(),
                settings.getDuplexPercent()),
            new ShopDtos.Features(
                settings.isColorEnabled(), settings.isDuplexEnabled(),
                settings.isLandscapeEnabled(), settings.isPageSelectionEnabled(),
                settings.isPdfEnabled(), settings.isWordEnabled(), settings.isImageEnabled()),
            paymentMethods,
            MAX_FILES,
            20,
            settings.getFileRetentionHours());
    }

    @Transactional
    public ShopSettings update(Shop shop, ShopDtos.UpdateSettingsRequest request) {
        shopRepository.lockById(shop.getId()).orElseThrow(() -> new NotFoundException("Shop was not found"));
        validatePrinter(shop.getId(), request.defaultPrinterId(), false);
        validatePrinter(shop.getId(), request.colorPrinterId(), !java.util.Objects.equals(request.colorPrinterId(), request.defaultPrinterId()));
        validatePrinter(shop.getId(), request.bwPrinterId(), false);
        ShopSettings settings = settingsFor(shop);
        int duplexPercent = request.duplexPercent() == null
            ? settings.getDuplexPercent() : request.duplexPercent();
        int retention = request.fileRetentionHours() == null
            ? settings.getFileRetentionHours() : request.fileRetentionHours();
        settings.apply(
            request.bwPerPage() == null ? settings.getBwPerPage() : request.bwPerPage(),
            request.colorPerPage() == null ? settings.getColorPerPage() : request.colorPerPage(),
            duplexPercent,
            request.colorEnabled(), request.duplexEnabled(), request.landscapeEnabled(),
            request.pageSelectionEnabled(), request.pdfEnabled(), request.wordEnabled(),
            request.imageEnabled(), request.cashEnabled(), request.bkashEnabled(),
            request.nagadEnabled(), request.rocketEnabled(), request.bkashNumber(),
            request.nagadNumber(), request.rocketNumber(), retention,
            request.defaultPrinterId(), request.colorPrinterId(), request.bwPrinterId());
        return settingsRepository.save(settings);
    }

    @Transactional(readOnly = true)
    public ShopDtos.SettingsView adminView(Shop shop) {
        ShopSettings settings = settingsRepository.findById(shop.getId())
            .orElseGet(() -> ShopSettings.defaults(shop.getId()));
        return new ShopDtos.SettingsView(
            settings.getBwPerPage(), settings.getColorPerPage(), settings.getDuplexPercent(),
            settings.isColorEnabled(), settings.isDuplexEnabled(), settings.isLandscapeEnabled(),
            settings.isPageSelectionEnabled(), settings.isPdfEnabled(), settings.isWordEnabled(),
            settings.isImageEnabled(), settings.isCashEnabled(), settings.isBkashEnabled(),
            settings.isNagadEnabled(), settings.isRocketEnabled(), settings.getBkashNumber(),
            settings.getNagadNumber(), settings.getRocketNumber(), settings.getFileRetentionHours(),
            settings.isQueuePaused(), settings.getDefaultPrinterId(), settings.getColorPrinterId(),
            settings.getBwPrinterId());
    }

    @Transactional
    public void setQueuePaused(Shop shop, boolean paused) {
        ShopSettings settings = settingsFor(shop);
        settings.setQueuePaused(paused);
        settingsRepository.save(settings);
    }

    @Transactional(readOnly = true)
    public ShopDtos.AdminShop adminShop(Shop shop) {
        return new ShopDtos.AdminShop(
            shop.getId(), shop.getShopCode(), shop.getName(), shop.getOwnerName(),
            shop.getPhone(), shop.getEmail(), shop.getAddress(), shop.getCurrency(),
            shop.getTimezone(), shop.getQrToken().toString(), qrCodeService.publicUrl(shop),
            "/api/v1/admin/shop/qr.png");
    }

    @Transactional
    public ShopDtos.AdminShop updateShop(Shop shop, ShopDtos.UpdateShopRequest request) {
        String name = request.name() == null || request.name().isBlank()
            ? shop.getName() : request.name().trim();
        shop.updateProfile(
            name,
            request.ownerName() == null ? shop.getOwnerName() : request.ownerName().trim(),
            request.phone() == null ? shop.getPhone() : request.phone().trim(),
            request.email() == null ? shop.getEmail() : request.email().trim(),
            request.address() == null ? shop.getAddress() : request.address().trim());
        shopRepository.save(shop);
        return adminShop(shop);
    }

    @Transactional
    public ShopDtos.AdminShop regenerateQr(Shop shop) {
        shop.rotateQrToken();
        shopRepository.save(shop);
        return adminShop(shop);
    }

    public PaymentMethod requirePaymentMethod(String code) {
        try {
            return PaymentMethod.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException("Unknown payment method " + code);
        }
    }

    @Transactional
    public void updatePrinterRouting(Shop shop, Long defaultId, Long colorId, Long bwId) {
        validatePrinter(shop.getId(), defaultId, false);
        validatePrinter(shop.getId(), colorId, !java.util.Objects.equals(colorId, defaultId));
        validatePrinter(shop.getId(), bwId, false);
        settingsFor(shop).setPrinterRouting(defaultId, colorId, bwId);
    }

    private void validatePrinter(Long shopId, Long printerId, boolean requireColor) {
        if (printerId == null) return;
        var printer = printerRepository.findByIdAndShopId(printerId, shopId)
            .orElseThrow(() -> new NotFoundException("Selected printer was not found"));
        if (requireColor && !printer.isColorSupported()) {
            throw new IllegalArgumentException("The selected color printer does not support color");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
