package com.smartprint.order;

import com.smartprint.common.NotFoundException;
import com.smartprint.payment.Payment;
import com.smartprint.payment.PaymentMethod;
import com.smartprint.payment.PaymentRepository;
import com.smartprint.printer.PrintJobRepository;
import com.smartprint.printer.PrintJob;
import com.smartprint.queue.QueueEntry;
import com.smartprint.queue.QueueEntryRepository;
import com.smartprint.realtime.RealtimeService;
import com.smartprint.shop.Shop;
import com.smartprint.shop.ShopDtos;
import com.smartprint.shop.ShopService;
import com.smartprint.shop.ShopRepository;
import com.smartprint.shop.ShopSettings;
import com.smartprint.shop.ShopSettingsService;
import com.smartprint.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final BigDecimal MAX_TOTAL = new BigDecimal("50000.00");
    private static final String NUMBER_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrderRepository orderRepository;
    private final OrderFileRepository orderFileRepository;
    private final PrintConfigRepository printConfigRepository;
    private final PaymentRepository paymentRepository;
    private final PrintJobRepository printJobRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final DocumentInspector documentInspector;
    private final DocumentConverter documentConverter;
    private final ObjectStorageService storage;
    private final ShopService shopService;
    private final ShopRepository shopRepository;
    private final ShopSettingsService settingsService;
    private final RealtimeService realtime;
    private final long maxFileSizeBytes;

    public OrderService(OrderRepository orderRepository,
                        OrderFileRepository orderFileRepository,
                        PrintConfigRepository printConfigRepository,
                        PaymentRepository paymentRepository,
                        QueueEntryRepository queueEntryRepository,
                        PrintJobRepository printJobRepository,
                        DocumentInspector documentInspector,
                        DocumentConverter documentConverter,
                        ObjectStorageService storage,
                        ShopService shopService,
                        ShopRepository shopRepository,
                        ShopSettingsService settingsService,
                        RealtimeService realtime,
                        @Value("${smartprint.upload.max-file-size-bytes:20971520}") long maxFileSizeBytes) {
        this.orderRepository = orderRepository;
        this.orderFileRepository = orderFileRepository;
        this.printConfigRepository = printConfigRepository;
        this.paymentRepository = paymentRepository;
        this.queueEntryRepository = queueEntryRepository;
        this.printJobRepository = printJobRepository;
        this.documentInspector = documentInspector;
        this.documentConverter = documentConverter;
        this.storage = storage;
        this.shopService = shopService;
        this.shopRepository = shopRepository;
        this.settingsService = settingsService;
        this.realtime = realtime;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    private record Prepared(
        String name,
        byte[] original,
        DocumentConverter.Converted converted,
        int pages,
        List<Integer> selectedPages,
        String pageSelection,
        BigDecimal lineTotal
    ) {}

    private record PreparedOrder(
        List<Prepared> files,
        PrintColorMode colorMode,
        PrintSideMode sideMode,
        PrintConfig.Orientation orientation,
        int copies,
        BigDecimal total
    ) {}

    @Transactional(readOnly = true)
    public OrderDtos.Quote quote(Shop shop, List<MultipartFile> files, PrintColorMode colorMode,
                                 PrintSideMode sideMode, int copies,
                                 PrintConfig.Orientation orientation, String pageSelection) {
        ShopSettings settings = settingsService.settingsFor(shop);
        PreparedOrder prepared = prepare(shop, settings, files, colorMode, sideMode,
            copies, orientation, pageSelection);
        List<OrderDtos.QuoteFile> quoteFiles = prepared.files().stream()
            .map(file -> new OrderDtos.QuoteFile(
                file.name(), file.converted().kind().name(), file.pages(),
                file.selectedPages().size(), file.pageSelection()))
            .toList();
        int totalPages = prepared.files().stream().mapToInt(Prepared::pages).sum();
        int totalSelected = prepared.files().stream().mapToInt(f -> f.selectedPages().size()).sum();
        return new OrderDtos.Quote(quoteFiles, totalPages, totalSelected, prepared.total(),
            shop.getCurrency(), new ShopDtos.Prices(settings.getBwPerPage(),
            settings.getColorPerPage(), settings.getDuplexPercent()));
    }

    @Transactional(readOnly = true)
    public byte[] draftPreview(Shop shop, MultipartFile file, PrintColorMode colorMode,
                               PrintConfig.Orientation orientation, String pageSelection, int previewIndex) {
        Prepared item = prepare(shop, settingsService.settingsFor(shop), List.of(file),
            colorMode, PrintSideMode.SIMPLEX, 1, orientation, pageSelection).files().getFirst();
        if (previewIndex >= item.selectedPages().size()) {
            throw new IllegalArgumentException("Preview page is outside the selection");
        }
        return documentConverter.thumbnail(item.converted().pdf(), item.selectedPages().get(previewIndex),
            orientation == PrintConfig.Orientation.LANDSCAPE, colorMode == PrintColorMode.BW);
    }

    @Transactional
    public OrderDtos.PublicOrder create(Shop shop, List<MultipartFile> files,
                                        PrintColorMode colorMode, PrintSideMode sideMode,
                                        int copies, PrintConfig.Orientation orientation,
                                        String pageSelection, PaymentMethod paymentMethod,
                                        String customerName, String customerPhone) {
        return create(shop, files, colorMode, sideMode, copies, orientation, pageSelection,
            paymentMethod, customerName, customerPhone, null, null, null);
    }

    @Transactional
    public OrderDtos.PublicOrder create(Shop shop, List<MultipartFile> files,
                                        PrintColorMode colorMode, PrintSideMode sideMode,
                                        int copies, PrintConfig.Orientation orientation,
                                        String pageSelection, PaymentMethod paymentMethod,
                                        String customerName, String customerPhone, String transactionId,
                                        BigDecimal expectedAmount, UUID requestKey) {
        if (requestKey != null) {
            var previous = orderRepository.findByShopIdAndRequestKey(shop.getId(), requestKey);
            if (previous.isPresent()) return publicOrder(previous.get().getPublicToken());
        }
        if (!"ACTIVE".equals(shop.getStatus())) {
            throw new IllegalStateException("This shop is not accepting orders");
        }
        ShopSettings settings = settingsService.settingsFor(shop);
        if (!settings.paymentMethodEnabled(paymentMethod)) {
            throw new IllegalArgumentException("The shop does not accept "
                + paymentMethod.name().toLowerCase(Locale.ROOT) + " payments");
        }

        String reference = transactionId == null ? "" : transactionId.trim().toUpperCase(Locale.ROOT);
        if (!reference.isEmpty() && (paymentMethod == PaymentMethod.CASH || !reference.matches("[A-Z0-9-]{6,64}"))) {
            throw new IllegalArgumentException("Enter a valid mobile payment transaction ID");
        }

        PreparedOrder prepared = prepare(shop, settings, files, colorMode, sideMode,
            copies, orientation, pageSelection);

        if (requestKey != null) {
            shopRepository.lockById(shop.getId()).orElseThrow(() -> new NotFoundException("Shop was not found"));
            var previous = orderRepository.findByShopIdAndRequestKey(shop.getId(), requestKey);
            if (previous.isPresent()) return publicOrder(previous.get().getPublicToken());
        }
        if (expectedAmount != null && expectedAmount.compareTo(prepared.total()) != 0) {
            throw new IllegalStateException("The shop's price changed. Refresh the quote and contact the shop if you have already paid.");
        }
        if (!reference.isEmpty() && paymentRepository.existsByProviderAndTransactionId(
                paymentMethod.name(), reference)) {
            throw new IllegalStateException("That transaction ID has already been used");
        }
        String orderNumber = newOrderNumber(shop);
        Order order = new Order(shop.getId(), orderNumber, ConfigurationMode.COMMON, CollectionMethod.COUNTER,
            prepared.total(), shop.getCurrency());
        order.setRequestKey(requestKey);
        order.setCustomer(trimTo(customerName, 150), trimTo(customerPhone, 30));
        // Abandoned or unverified orders are deleted after the shop's retention window too.
        order.schedulePurge(java.time.Instant.now().plus(
            settings.getFileRetentionHours(), java.time.temporal.ChronoUnit.HOURS));
        orderRepository.save(order);

        List<String> uploadedKeys = new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) return;
                for (String key : uploadedKeys) {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException ex) {
                        log.warn("Could not clean up storage after a rolled back order");
                    }
                }
            }
        });

        int index = 1;
        for (Prepared file : prepared.files()) {
            String base = "shops/" + shop.getId() + "/orders/" + orderNumber + "/" + index;
            String originalKey = base + "-original." + extensionFor(file.converted().mimeType());
            String printKey = base + "-print.pdf";
            uploadedKeys.add(originalKey);
            storage.put(originalKey, new ByteArrayInputStream(file.original()),
                file.original().length, file.converted().mimeType());
            uploadedKeys.add(printKey);
            storage.put(printKey, new ByteArrayInputStream(file.converted().pdf()),
                file.converted().pdf().length, "application/pdf");

            OrderFile orderFile = orderFileRepository.save(new OrderFile(
                order.getId(), file.name(), originalKey, file.converted().mimeType(),
                (long) file.original().length, file.pages(), sha256Of(file.converted().pdf())));
            orderFile.markProcessed(printKey);
            orderFileRepository.save(orderFile);

            printConfigRepository.save(new PrintConfig(
                order.getId(), orderFile.getId(), colorMode, sideMode, copies,
                orientation, file.pageSelection(), file.pages(), file.selectedPages().size()));
            index++;
        }

        Payment payment = new Payment(order.getId(), paymentMethod, prepared.total());
        if (!reference.isEmpty()) {
            payment.submitReference(reference);
        }
        paymentRepository.save(payment);
        realtime.shopChanged(shop.getId());
        return toPublic(order, payment, shop, settings);
    }

    private PreparedOrder prepare(Shop shop, ShopSettings settings, List<MultipartFile> files,
                                  PrintColorMode colorMode, PrintSideMode sideMode, int copies,
                                  PrintConfig.Orientation orientation, String pageSelection) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one document is required");
        }
        if (files.size() > ShopSettingsService.MAX_FILES) {
            throw new IllegalArgumentException("A maximum of " + ShopSettingsService.MAX_FILES
                + " files is allowed per order");
        }
        if (copies < 1 || copies > 100) {
            throw new IllegalArgumentException("Copies must be between 1 and 100");
        }
        if (colorMode == PrintColorMode.COLOR && !settings.isColorEnabled()) {
            throw new IllegalArgumentException("Colour printing is not available at this shop");
        }
        if (sideMode == PrintSideMode.DUPLEX && !settings.isDuplexEnabled()) {
            throw new IllegalArgumentException("Double-sided printing is not available at this shop");
        }
        if (orientation == PrintConfig.Orientation.LANDSCAPE && !settings.isLandscapeEnabled()) {
            throw new IllegalArgumentException("Landscape printing is not available at this shop");
        }
        if (pageSelection != null && pageSelection.length() > 2000) {
            throw new IllegalArgumentException("The page selection is too long");
        }
        boolean customSelection = pageSelection != null && !pageSelection.isBlank()
            && !PageSelection.ALL.equalsIgnoreCase(pageSelection.trim());
        if (customSelection && !settings.isPageSelectionEnabled()) {
            throw new IllegalArgumentException("Page selection is not available at this shop");
        }

        BigDecimal rate = settings.rateFor(colorMode);
        BigDecimal multiplier = sideMode == PrintSideMode.DUPLEX
            ? BigDecimal.valueOf(settings.getDuplexPercent())
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
            : BigDecimal.ONE;

        List<Prepared> prepared = new ArrayList<>(files.size());
        BigDecimal total = BigDecimal.ZERO;
        for (MultipartFile file : files) {
            String name = displayName(file);
            if (file.getSize() <= 0) {
                throw new IllegalArgumentException("'" + name + "' is empty");
            }
            if (file.getSize() > maxFileSizeBytes) {
                throw new IllegalArgumentException("'" + name + "' is larger than the "
                    + (maxFileSizeBytes / (1024 * 1024)) + " MB limit");
            }
            byte[] original;
            try {
                original = file.getBytes();
            } catch (IOException ex) {
                throw new IllegalArgumentException("'" + name + "' could not be read; upload it again");
            }

            DocumentConverter.Kind format = documentConverter.detectKind(name, original);
            ShopSettings.FileKind kind = ShopSettings.FileKind.valueOf(format.name());
            if (!settings.fileTypeEnabled(kind)) {
                throw new IllegalArgumentException(kind.name() + " files are not accepted at this shop");
            }
            DocumentConverter.Converted converted = documentConverter.toPdf(name, original, format);

            int pages = documentInspector.inspect("document.pdf", converted.pdf()).pageCount();
            List<Integer> selected = PageSelection.parse(pageSelection, pages);
            String normalised = PageSelection.normalise(pageSelection, pages);
            BigDecimal line = rate
                .multiply(BigDecimal.valueOf(selected.size()))
                .multiply(BigDecimal.valueOf(copies))
                .multiply(multiplier)
                .setScale(2, RoundingMode.HALF_UP);
            total = total.add(line);
            prepared.add(new Prepared(name, original, converted, pages,
                selected, normalised, line));
        }

        total = total.setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("The order total must be greater than zero");
        }
        if (total.compareTo(MAX_TOTAL) > 0) {
            throw new IllegalArgumentException("The order total exceeds the "
                + shop.getCurrency() + " " + MAX_TOTAL.toPlainString() + " limit");
        }
        return new PreparedOrder(prepared, colorMode, sideMode, orientation, copies, total);
    }

    @Transactional(readOnly = true)
    public OrderDtos.PublicOrder publicOrder(UUID publicToken) {
        Order order = orderRepository.findByPublicToken(publicToken)
            .orElseThrow(() -> new NotFoundException("This order link is no longer valid"));
        Payment payment = paymentRepository.findByOrderId(order.getId())
            .orElseThrow(() -> new IllegalStateException("Order has no payment record"));
        Shop shop = shopService.requireById(order.getShopId());
        return toPublic(order, payment, shop, settingsService.settingsFor(shop));
    }

    @Transactional(readOnly = true)
    public List<OrderDtos.AdminOrder> adminOrders(Long shopId, OrderStatus status, int limit, int page) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return orderRepository.findRecentForShop(shopId, status, PageRequest.of(page, safeLimit))
            .stream()
            .map(order -> toAdmin(order, shopId))
            .toList();
    }

    @Transactional(readOnly = true)
    public OrderDtos.AdminOrder adminOrder(Long shopId, String orderNumber) {
        Order order = orderRepository.findByOrderNumberAndShopId(orderNumber, shopId)
            .orElseThrow(() -> new NotFoundException("Order " + orderNumber + " was not found"));
        return toAdmin(order, shopId);
    }

    @Transactional(readOnly = true)
    public byte[] preview(UUID publicToken, Long fileId) {
        Order order = orderRepository.findByPublicToken(publicToken)
            .orElseThrow(() -> new NotFoundException("This order link is no longer valid"));
        return renderFile(order, fileId);
    }

    @Transactional(readOnly = true)
    public byte[] adminPreview(Long shopId, String orderNumber, Long fileId) {
        Order order = orderRepository.findByOrderNumberAndShopId(orderNumber, shopId)
            .orElseThrow(() -> new NotFoundException("Order " + orderNumber + " was not found"));
        return renderFile(order, fileId);
    }

    @Transactional(readOnly = true)
    public byte[] renderFile(Order order, Long fileId) {
        OrderFile file = orderFileRepository.findByIdAndOrderId(fileId, order.getId())
            .orElseThrow(() -> new NotFoundException("File " + fileId + " was not found"));
        if (file.isPurged()) {
            throw new NotFoundException("This file has already been deleted");
        }
        PrintConfig config = printConfigRepository.findByOrderFileId(file.getId()).orElse(null);
        boolean originalOnly = file.getProcessedStorageKey() == null;
        String key = originalOnly ? file.getStorageKey() : file.getProcessedStorageKey();
        try (var stream = storage.get(key)) {
            byte[] bytes = stream.readAllBytes();
            byte[] pdf = originalOnly ? documentConverter.toPdf(file.getOriginalName(), bytes).pdf() : bytes;
            int pages = file.getPageCount() == null
                ? documentInspector.inspect(file.getOriginalName(), pdf).pageCount() : file.getPageCount();
            List<Integer> selected = PageSelection.parse(
                config == null ? PageSelection.ALL : config.getPageSelection(), pages);
            boolean landscape = config != null && config.getOrientation() == PrintConfig.Orientation.LANDSCAPE;
            return documentConverter.render(pdf, selected, landscape);
        } catch (IOException ex) {
            throw new IllegalStateException("The document could not be read");
        }
    }

    public OrderDtos.PublicOrder toPublic(Order order, Payment payment, Shop shop,
                                          ShopSettings settings) {
        List<OrderDtos.FileView> files = fileViews(order, true);
        QueueEntry queue = queueEntryRepository.findByOrderId(order.getId()).orElse(null);
        return new OrderDtos.PublicOrder(
            order.getOrderNumber(),
            order.getPublicToken().toString(),
            order.getStatus().name(),
            statusLabel(order, payment, queue),
            order.getTotalAmount(),
            order.getCurrency(),
            order.getCreatedAt(),
            order.getUpdatedAt(),
            files,
            paymentView(payment, order, shop, settings),
            queueView(queue),
            new OrderDtos.ShopView(shop.getName(), shop.getPhone(), shop.getAddress(), shop.getCurrency()));
    }

    public OrderDtos.AdminOrder toAdmin(Order order, Long shopId) {
        Payment payment = paymentRepository.findByOrderId(order.getId())
            .orElseThrow(() -> new IllegalStateException("Order has no payment record"));
        Shop shop = shopService.requireById(shopId);
        ShopSettings settings = settingsService.settingsFor(shop);
        QueueEntry queue = queueEntryRepository.findByOrderId(order.getId()).orElse(null);
        String printFailure = queue == null || !QueueEntry.FAILED.equals(queue.getStatus()) ? null
            : printJobRepository.findByQueueEntryId(queue.getId()).stream()
                .map(PrintJob::getFailureReason).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return new OrderDtos.AdminOrder(
            order.getId(), order.getOrderNumber(), order.getPublicToken().toString(),
            order.getStatus().name(), statusLabel(order, payment, queue),
            order.getCustomerName(), order.getCustomerPhone(), order.getTotalAmount(),
            order.getCurrency(), order.getCreatedAt(), order.getUpdatedAt(), order.getPurgeAt(), printFailure,
            fileViews(order, false), paymentView(payment, order, shop, settings), queueView(queue));
    }

    private List<OrderDtos.FileView> fileViews(Order order, boolean publicUrls) {
        List<OrderFile> files = orderFileRepository.findByOrderIdOrderByIdAsc(order.getId());
        List<OrderDtos.FileView> views = new ArrayList<>(files.size());
        for (OrderFile file : files) {
            PrintConfig config = printConfigRepository.findByOrderFileId(file.getId()).orElse(null);
            String preview = publicUrls
                ? "/api/v1/public/orders/" + order.getPublicToken() + "/files/" + file.getId() + "/preview.pdf"
                : "/api/v1/admin/orders/" + order.getOrderNumber() + "/files/" + file.getId() + "/preview.pdf";
            views.add(new OrderDtos.FileView(
                file.getId(), file.getOriginalName(), kindOf(file.getMimeType()),
                file.getMimeType(), file.getPageCount() == null ? 0 : file.getPageCount(),
                config == null || config.getSelectedPageCount() == null
                    ? (file.getPageCount() == null ? 0 : file.getPageCount())
                    : config.getSelectedPageCount(),
                config == null ? null : config.getColorMode().name(),
                config == null ? null : config.getSideMode().name(),
                config == null ? null : config.getOrientation().name(),
                config == null ? PageSelection.ALL : config.getPageSelection(),
                config == null ? 1 : config.getCopies(),
                file.isPurged() ? null : preview));
        }
        return views;
    }

    private OrderDtos.PaymentView paymentView(Payment payment, Order order, Shop shop,
                                              ShopSettings settings) {
        return new OrderDtos.PaymentView(
            payment.getId(), payment.getPaymentMethod().name(), payment.getStatus().name(),
            payment.getProvider(), payment.getAmount(), payment.getTransactionId(),
            payment.getRejectionReason(), instructions(shop, order, payment, settings));
    }

    private OrderDtos.QueueView queueView(QueueEntry queue) {
        if (queue == null) return null;
        boolean active = QueueEntry.QUEUED.equals(queue.getStatus());
        Integer ahead = active
            ? (int) queueEntryRepository.countAhead(queue.getShopId(), queue.priority())
            : null;
        return new OrderDtos.QueueView(queue.getId(), queue.getQueueNumber(), queue.getQueueDate(),
            queue.getStatus(), ahead == null ? null : ahead + 1, ahead);
    }

    private String statusLabel(Order order, Payment payment, QueueEntry queue) {
        return switch (order.getStatus()) {
            case PAYMENT_PENDING, UPLOADED, PROCESSING, NEEDS_REVIEW -> "Waiting for payment approval";
            case PAYMENT_APPROVED -> "Payment approved — joining the print queue";
            case QUEUED -> queue == null ? "Payment approved — waiting in queue"
                : "Payment approved — you are #" + (queueEntryRepository.countAhead(
                    queue.getShopId(), queue.priority()) + 1) + " in line, waiting for a compatible printer";
            case PRINTING -> "Printing now";
            case COMPLETED -> "Print processing completed";
            case COLLECTED -> "Collected — thank you";
            case PAYMENT_REJECTED -> "Payment rejected"
                + (payment.getRejectionReason() == null ? "" : " — " + payment.getRejectionReason());
            case CANCELLED -> queue == null && order.getPurgeAt() == null
                ? "Order expired and the files were deleted" : "Order cancelled";
            case FAILED -> "Printing failed — the shop has been notified";
            default -> order.getStatus().name().replace('_', ' ').toLowerCase(Locale.ROOT);
        };
    }

    public String instructions(Shop shop, Order order, Payment payment, ShopSettings settings) {
        String amount = payment.getAmount().toPlainString() + " " + shop.getCurrency();
        String orderNo = order.getOrderNumber();
        return switch (payment.getPaymentMethod()) {
            case CASH -> "Pay " + amount + " at the counter and quote order " + orderNo + ".";
            case BKASH -> walletInstructions("bKash", amount, settings.getBkashNumber(), orderNo);
            case NAGAD -> walletInstructions("Nagad", amount, settings.getNagadNumber(), orderNo);
            case ROCKET -> walletInstructions("Rocket", amount, settings.getRocketNumber(), orderNo);
        };
    }

    private String walletInstructions(String provider, String amount, String number, String orderNo) {
        String destination = number == null || number.isBlank()
            ? "the shop's " + provider + " number" : number;
        return "Send " + amount + " via " + provider + " to " + destination
            + ", then submit the transaction ID below. Use order " + orderNo + " as the reference.";
    }

    private String kindOf(String mimeType) {
        if (mimeType == null) return "PDF";
        if (mimeType.startsWith("image/")) return "IMAGE";
        if (mimeType.equals("application/pdf")) return "PDF";
        return "WORD";
    }

    private String extensionFor(String mimeType) {
        if (mimeType.contains("png")) return "png";
        if (mimeType.contains("jpeg")) return "jpg";
        if (mimeType.contains("msword")) return "doc";
        if (mimeType.contains("wordprocessingml")) return "docx";
        return "pdf";
    }

    private String displayName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) return "document";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        base = base.trim();
        if (base.isEmpty()) return "document";
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    private String trimTo(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private String sha256Of(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String newOrderNumber(Shop shop) {
        String date = LocalDate.now(ZoneId.of(shop.getTimezone()))
            .format(DateTimeFormatter.BASIC_ISO_DATE);
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder suffix = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                suffix.append(NUMBER_ALPHABET.charAt(RANDOM.nextInt(NUMBER_ALPHABET.length())));
            }
            String candidate = ("SP-" + date + "-" + suffix).toUpperCase(Locale.ROOT);
            if (!orderRepository.existsByOrderNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate an order number; try again");
    }
}
