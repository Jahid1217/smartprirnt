package com.smartprint.order;

import com.smartprint.payment.PaymentMethod;
import com.smartprint.realtime.RealtimeService;
import com.smartprint.shop.Shop;
import com.smartprint.shop.ShopService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public")
@Validated
public class PublicOrderController {
    private final ShopService shopService;
    private final OrderService orderService;
    private final RealtimeService realtime;

    public PublicOrderController(ShopService shopService, OrderService orderService,
                                 RealtimeService realtime) {
        this.shopService = shopService;
        this.orderService = orderService;
        this.realtime = realtime;
    }

    @PostMapping(value = "/shops/{qrToken}/quote", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OrderDtos.Quote quote(
            @PathVariable UUID qrToken,
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam PrintColorMode colorMode,
            @RequestParam PrintSideMode sideMode,
            @RequestParam @Min(1) @Max(100) Integer copies,
            @RequestParam(defaultValue = "PORTRAIT") PrintConfig.Orientation orientation,
            @RequestParam(defaultValue = "ALL") String pageSelection) {
        Shop shop = shopService.requireByQrToken(qrToken);
        return orderService.quote(shop, files, colorMode, sideMode, copies,
            orientation, pageSelection);
    }

    @PostMapping(value = "/shops/{qrToken}/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> draftPreview(
            @PathVariable UUID qrToken,
            @RequestPart("file") MultipartFile file,
            @RequestParam PrintColorMode colorMode,
            @RequestParam PrintConfig.Orientation orientation,
            @RequestParam(defaultValue = "ALL") String pageSelection,
            @RequestParam(defaultValue = "0") @Min(0) int previewIndex) {
        byte[] image = orderService.draftPreview(shopService.requireByQrToken(qrToken),
            file, colorMode, orientation, pageSelection, previewIndex);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.IMAGE_JPEG).body(image);
    }

    @PostMapping(value = "/shops/{qrToken}/orders", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OrderDtos.PublicOrder> create(
            @PathVariable UUID qrToken,
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam PrintColorMode colorMode,
            @RequestParam PrintSideMode sideMode,
            @RequestParam @Min(1) @Max(100) Integer copies,
            @RequestParam(defaultValue = "PORTRAIT") PrintConfig.Orientation orientation,
            @RequestParam(defaultValue = "ALL") String pageSelection,
            @RequestParam PaymentMethod paymentMethod,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String customerPhone,
            @RequestParam(required = false) String transactionId,
            @RequestParam(required = false) BigDecimal expectedAmount,
            @RequestParam(required = false) UUID requestKey) {
        Shop shop = shopService.requireByQrToken(qrToken);
        OrderDtos.PublicOrder order = orderService.create(shop, files, colorMode, sideMode,
            copies, orientation, pageSelection, paymentMethod, customerName, customerPhone, transactionId, expectedAmount, requestKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping("/orders/{publicToken}")
    public OrderDtos.PublicOrder order(@PathVariable UUID publicToken) {
        return orderService.publicOrder(publicToken);
    }

    @GetMapping(value = "/orders/{publicToken}/files/{fileId}/preview.pdf",
        produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> preview(@PathVariable UUID publicToken,
                                          @PathVariable Long fileId) {
        byte[] pdf = orderService.preview(publicToken, fileId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf);
    }

    @GetMapping(value = "/orders/{publicToken}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID publicToken) {
        // Validate the token before opening the stream; unknown links get a 404.
        orderService.publicOrder(publicToken);
        return realtime.subscribeOrder(publicToken);
    }
}
