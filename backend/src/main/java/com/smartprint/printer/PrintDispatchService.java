package com.smartprint.printer;

import com.smartprint.auth.InvalidCredentialsException;
import com.smartprint.common.NotFoundException;
import com.smartprint.order.Order;
import com.smartprint.order.OrderFile;
import com.smartprint.order.OrderFileRepository;
import com.smartprint.order.OrderRepository;
import com.smartprint.order.OrderService;
import com.smartprint.order.PrintColorMode;
import com.smartprint.order.PrintConfig;
import com.smartprint.order.PrintConfigRepository;
import com.smartprint.order.PrintSideMode;
import com.smartprint.queue.QueueEntry;
import com.smartprint.queue.QueueEntryRepository;
import com.smartprint.queue.QueueService;
import com.smartprint.realtime.RealtimeService;
import com.smartprint.shop.Shop;
import com.smartprint.shop.ShopService;
import com.smartprint.shop.ShopSettings;
import com.smartprint.shop.ShopSettingsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PrintDispatchService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PrintDispatchService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final PrintAgentRepository agentRepository;
    private final PrinterRepository printerRepository;
    private final PrintJobRepository jobRepository;
    private final OrderRepository orderRepository;
    private final OrderFileRepository orderFileRepository;
    private final PrintConfigRepository configRepository;
    private final OrderService orderService;
    private final QueueService queueService;
    private final QueueEntryRepository queueEntryRepository;
    private final ShopService shopService;
    private final ShopSettingsService settingsService;
    private final RealtimeService realtime;
    private final PrintAttemptHistory history;
    private final PasswordEncoder passwordEncoder;

    public PrintDispatchService(PrintAgentRepository agentRepository,
                                PrinterRepository printerRepository,
                                PrintJobRepository jobRepository,
                                OrderRepository orderRepository,
                                OrderFileRepository orderFileRepository,
                                PrintConfigRepository configRepository,
                                OrderService orderService,
                                QueueService queueService,
                                QueueEntryRepository queueEntryRepository,
                                ShopService shopService,
                                ShopSettingsService settingsService,
                                RealtimeService realtime,
                                PasswordEncoder passwordEncoder, PrintAttemptHistory history) {
        this.agentRepository = agentRepository;
        this.printerRepository = printerRepository;
        this.jobRepository = jobRepository;
        this.orderRepository = orderRepository;
        this.orderFileRepository = orderFileRepository;
        this.configRepository = configRepository;
        this.orderService = orderService;
        this.queueService = queueService;
        this.queueEntryRepository = queueEntryRepository;
        this.shopService = shopService;
        this.settingsService = settingsService;
        this.realtime = realtime;
        this.passwordEncoder = passwordEncoder;
        this.history = history;
    }

    @Transactional
    public PrinterDtos.CreatedAgent createAgent(Long shopId, String name) {
        Shop shop = shopService.requireById(shopId);
        String code = shop.getShopCode() + "-AGENT-" + token(6);
        String secret = token(32);
        PrintAgent agent = agentRepository.save(new PrintAgent(
            shopId, code, passwordEncoder.encode(secret), name.trim()));
        return new PrinterDtos.CreatedAgent(agent.getAgentCode(), secret, agent.getName());
    }

    @Transactional(readOnly = true)
    public List<PrinterDtos.AgentView> agents(Long shopId) {
        return agentRepository.findByShopIdOrderByCreatedAtAsc(shopId).stream()
            .map(agent -> new PrinterDtos.AgentView(
                agent.getId(), agent.getAgentCode(), agent.getName(), status(agent.getStatus(), agent.getLastSeenAt()),
                agent.getLastSeenAt(), agent.getLastPrinterCount(), agent.getDiscoveryError(), agent.getPlatform(), agent.getCreatedAt()))
            .toList();
    }

    @Transactional(readOnly = true)
    public PrinterDtos.Discovery discovery(Long shopId) {
        var settings = settingsService.adminView(shopService.requireById(shopId));
        var routing = new PrinterDtos.Routing(settings.defaultPrinterId(), settings.colorPrinterId(), settings.bwPrinterId());
        var agents = agents(shopId);
        var printers = printers(shopId);
        log.debug("Printer discovery snapshot for shop {} contains {} agents and {} printers",
            shopId, agents.size(), printers.size());
        return new PrinterDtos.Discovery(agents, printers, routing,
            queueEntryRepository.countByShopIdAndStatus(shopId, QueueEntry.QUEUED));
    }

    @Transactional
    public PrinterDtos.Routing saveRouting(Long shopId, PrinterDtos.Routing routing) {
        queueService.lockShop(shopId);
        settingsService.updatePrinterRouting(shopService.requireById(shopId), routing.defaultPrinterId(),
            routing.colorPrinterId(), routing.bwPrinterId());
        return routing;
    }

    @Transactional(readOnly = true)
    public List<PrinterDtos.PrinterView> printers(Long shopId) {
        var jobs = reservations(shopId);
        var entryIds = jobs.stream().map(PrintJob::getQueueEntryId).collect(Collectors.toSet());
        var entries = queueEntryRepository.findAllById(entryIds).stream()
            .collect(Collectors.toMap(QueueEntry::getId, QueueEntry::getOrderId));
        var orders = orderRepository.findAllById(entries.values()).stream()
            .collect(Collectors.toMap(Order::getId, Order::getOrderNumber));
        var activeByPrinter = jobs.stream().filter(job -> job.getPrinterId() != null)
            .collect(Collectors.groupingBy(PrintJob::getPrinterId, Collectors.mapping(
                job -> orders.get(entries.get(job.getQueueEntryId())), Collectors.toSet())));
        var printers = printerRepository.findByShopIdOrderByNameAsc(shopId);
        var unavailable = unavailableResources(printers, jobs, Instant.now().minusSeconds(35));
        return printers.stream().map(printer -> view(printer,
            activeByPrinter.getOrDefault(printer.getId(), Set.of()).stream()
                .filter(Objects::nonNull).sorted().toList(), unavailable)).toList();
    }

    @Transactional
    public PrinterDtos.PrinterView setEnabled(Long shopId, Long printerId, boolean enabled) {
        queueService.lockShop(shopId);
        Printer printer = printerRepository.findByIdAndShopId(printerId, shopId)
            .orElseThrow(() -> new NotFoundException("Printer " + printerId + " was not found"));
        printer.setEnabled(enabled);
        printerRepository.save(printer);
        return view(printer, List.of(), Set.of());
    }

    @Transactional
    public PrinterDtos.AgentStatus heartbeat(String agentCode, String agentToken,
                                             List<PrinterDtos.PrinterReport> reports, String discoveryError, String platform) {
        PrintAgent agent = authenticate(agentCode, agentToken);
        queueService.lockShop(agent.getShopId());
        boolean formerlyOnline = "ONLINE".equals(status(agent.getStatus(), agent.getLastSeenAt()));
        int previousPrinterCount = agent.getLastPrinterCount();
        String previousError = agent.getDiscoveryError();
        List<PrinterDtos.PrinterReport> safeReports = reports == null ? List.of() : reports;
        Set<String> reportedCodes = new HashSet<>();
        for (PrinterDtos.PrinterReport report : safeReports) {
            if (report.code() == null || report.code().isBlank()) continue;
            String code = agent.getId() + ":" + report.code().trim();
            String legacyCode = report.code().trim();
            Printer legacy = printerRepository.findByShopIdAndPrinterCode(agent.getShopId(), legacyCode)
                .filter(candidate -> agent.getId().equals(candidate.getAgentId())).orElse(null);
            String printerCode = legacy == null ? code : legacyCode;
            if (!reportedCodes.add(printerCode)) continue;
            Printer printer = printerRepository
                .findByShopIdAndPrinterCode(agent.getShopId(), printerCode)
                .orElseGet(() -> new Printer(agent.getShopId(), agent.getId(), printerCode,
                    report.name(), report.connectionType(), report.uri()));
            printer.updateFromAgent(agent.getId(), report.name().trim(),
                report.connectionType().trim(), report.uri().trim(),
                report.status() == null || report.status().isBlank() ? "ONLINE" : report.status(),
                report.colorSupported(), report.duplexSupported());
            printer.setAgentDefault(report.agentDefault());
            printer.setDeviceMetadata(report.virtualPrinter(), report.deviceGroup());
            printer.reportWorkload(report.model(), report.pendingJobs(), report.activeJobs());
            printer.setPortName(report.portName());
            printerRepository.save(printer);
        }
        for (Printer printer : printerRepository.findByShopIdOrderByNameAsc(agent.getShopId())) {
            if (agent.getId().equals(printer.getAgentId())
                && !reportedCodes.contains(printer.getPrinterCode())
                && !"OFFLINE".equals(printer.getStatus())) {
                printer.markOffline();
                printerRepository.save(printer);
            }
        }
        agent.seen(reportedCodes.size());
        agent.setDiscoveryError(discoveryError);
        agent.setPlatform("WINDOWS".equals(platform) ? "WINDOWS" : "CUPS");
        agentRepository.save(agent);
        if (!formerlyOnline || previousPrinterCount != reportedCodes.size()
            || !Objects.equals(previousError, discoveryError)) {
            log.info("Printer heartbeat for shop {} agent {}: received={}, synchronized={}, discoveryError={}",
                agent.getShopId(), agent.getId(), safeReports.size(), reportedCodes.size(), discoveryError != null);
        }
        Shop shop = shopService.requireById(agent.getShopId());
        boolean paused = settingsService.settingsFor(shop).isQueuePaused();
        return new PrinterDtos.AgentStatus(agent.getId(), agent.getShopId(), shop.getName(),
            agent.getStatus(), paused, reportedCodes.size());
    }

    @Transactional
    public Optional<PrinterDtos.AgentJob> claimNext(String agentCode, String agentToken) {
        return claim(agentCode, agentToken, 1).stream().findFirst();
    }

    @Transactional
    public List<PrinterDtos.AgentJob> claimAssignments(String agentCode, String agentToken) {
        return claim(agentCode, agentToken, 100);
    }

    private List<PrinterDtos.AgentJob> claim(String agentCode, String agentToken, int limit) {
        PrintAgent agent = authenticate(agentCode, agentToken);
        long shopId = agent.getShopId();
        queueService.lockShop(shopId);
        ShopSettings settings = settingsService.settingsFor(shopService.requireById(shopId));
        var printers = printerRepository.findByShopIdOrderByNameAsc(shopId);
        var printerById = printers.stream().collect(Collectors.toMap(Printer::getId, Function.identity()));
        var active = queueEntryRepository.findActiveForShop(shopId);
        var cutoff = Instant.now().minusSeconds(35);
        var unavailable = unavailableResources(printers, reservations(shopId), cutoff);
        List<PrinterDtos.AgentJob> assignments = new ArrayList<>();

        for (QueueEntry entry : active) {
            if (assignments.size() >= limit) break;
            boolean resuming = QueueEntry.PRINTING.equals(entry.getStatus());
            if (!resuming && (settings.isQueuePaused() || printers.stream().noneMatch(printer ->
                agent.getId().equals(printer.getAgentId()) && printer.isEnabled()
                    && "FREE".equals(PrinterSelection.availability(printer, cutoff))
                    && !unavailable.contains(PrinterSelection.resource(printer))))) break;

            var existing = jobRepository.findByQueueEntryId(entry.getId()).stream()
                .collect(Collectors.toMap(PrintJob::getOrderFileId, Function.identity()));
            if (resuming && !assignedTo(agent, existing.values())) continue;

            Order order = orderRepository.findById(entry.getOrderId()).orElseThrow();
            var configs = configRepository.findByOrderId(order.getId()).stream()
                .collect(Collectors.toMap(PrintConfig::getOrderFileId, Function.identity()));

            List<ResolvedFile> resolved = new ArrayList<>();
            boolean blocked = false;
            for (OrderFile file : orderFileRepository.findByOrderIdOrderByIdAsc(order.getId())) {
                PrintJob job = existing.get(file.getId());
                if (job != null && PrintJob.DONE.equals(job.getStatus())) continue;
                PrintConfig config = configs.get(file.getId());
                if (config == null || (!resuming && file.isPurged())) {
                    if (resuming) failEntry(entry, shopId, "The assigned document configuration is missing");
                    else queueService.markFailed(entry, shopId);
                    blocked = true;
                    break;
                }
                Printer printer = resuming
                    ? printerById.get(job == null ? null : job.getPrinterId())
                    : PrinterSelection.select(printers, unavailable, agent.getId(), pinned(settings, config.getColorMode()),
                        config.getColorMode() == PrintColorMode.COLOR, config.getSideMode() == PrintSideMode.DUPLEX, cutoff);
                if (printer == null) {
                    if (resuming) failEntry(entry, shopId, "The assigned printer is missing. Check printer output before retrying.");
                    blocked = true;
                    break;
                }
                resolved.add(new ResolvedFile(file, config, printer, job));
            }
            if (blocked) continue;
            if (!resuming && resolved.isEmpty()) {
                queueService.markFailed(entry, shopId);
                continue;
            }
            if (!resuming) {
                queueService.markPrinting(entry, shopId);
                for (ResolvedFile item : resolved) {
                    unavailable.add(PrinterSelection.resource(item.printer()));
                    item.printer().markAssigned(Instant.now());
                }
            }
            assignments.add(payload(agent, entry, order, resolved, resuming));
        }
        return assignments;
    }

    private List<PrintJob> reservations(long shopId) {
        return jobRepository.findByShopIdAndStatus(shopId, PrintJob.PRINTING);
    }

    private Set<String> unavailableResources(List<Printer> printers, List<PrintJob> jobs, Instant cutoff) {
        var unavailable = new HashSet<String>();
        var reservedIds = jobs.stream().map(PrintJob::getPrinterId).collect(Collectors.toSet());
        for (Printer printer : printers) {
            if (reservedIds.contains(printer.getId()) || "BUSY".equals(PrinterSelection.availability(printer, cutoff))) {
                unavailable.add(PrinterSelection.resource(printer));
            }
        }
        return unavailable;
    }

    private PrinterDtos.AgentJob payload(PrintAgent agent, QueueEntry entry, Order order,
                                         List<ResolvedFile> resolved, boolean resuming) {
        List<PrinterDtos.AgentFile> payload = new ArrayList<>();
        for (ResolvedFile item : resolved) {
            PrintJob job = item.job();
            if (!resuming) {
                if (job == null) job = new PrintJob(agent.getShopId(), entry.getId(),
                    item.file().getId(), item.printer().getId());
                job.claim(agent.getId(), item.printer().getId(),
                    order.getOrderNumber() + "-" + item.file().getId());
                jobRepository.saveAndFlush(job);
                history.started(job);
            }
            payload.add(new PrinterDtos.AgentFile(
                item.file().getId(), submissionKey(job), item.file().getOriginalName(),
                item.config().getCopies(), item.config().getColorMode().name(),
                item.config().getSideMode().name(), item.config().getOrientation().name(),
                item.config().getPageSelection(),
                item.config().getSelectedPageCount() == null ? 0 : item.config().getSelectedPageCount(),
                item.printer().getId(), item.printer().getName(), item.printer().getPrinterUri(),
                "/api/v1/agent/jobs/" + entry.getId() + "/files/" + item.file().getId() + ".pdf"));
        }
        return new PrinterDtos.AgentJob(entry.getId(), entry.getQueueNumber(), order.getOrderNumber(),
            order.getCustomerName(), assignmentKey(entry), payload);
    }

    private record ResolvedFile(OrderFile file, PrintConfig config, Printer printer, PrintJob job) {}

    @Transactional
    public void complete(String agentCode, String agentToken, Long queueEntryId, String key) {
        PrintAgent agent = authenticate(agentCode, agentToken);
        queueService.lockShop(agent.getShopId());
        QueueEntry entry = requireEntry(agent.getShopId(), queueEntryId);
        requireAssignment(agent, entry);
        requireAttempt(entry, key);
        if (QueueEntry.COMPLETED.equals(entry.getStatus())) return;
        requirePrinting(entry);
        if (jobRepository.findByQueueEntryId(entry.getId()).stream()
            .anyMatch(job -> !PrintJob.DONE.equals(job.getStatus()))) {
            throw new IllegalStateException("Some files are still printing");
        }
        queueService.markCompleted(entry, agent.getShopId());
    }

    @Transactional
    public void completeFile(String agentCode, String agentToken, Long queueEntryId,
                             Long fileId, String key) {
        PrintAgent agent = authenticate(agentCode, agentToken);
        queueService.lockShop(agent.getShopId());
        QueueEntry entry = requireEntry(agent.getShopId(), queueEntryId);
        requirePrinting(entry);
        PrintJob job = jobRepository.findByQueueEntryIdAndOrderFileId(entry.getId(), fileId)
            .filter(candidate -> agent.getId().equals(candidate.getAgentId())
                && submissionKey(candidate).equals(key))
            .orElseThrow(() -> new NotFoundException("Print file was not assigned to this agent"));
        if (PrintJob.DONE.equals(job.getStatus())) return;
        job.done();
        history.finished(job);
        jobRepository.save(job);
    }

    private String submissionKey(PrintJob job) {
        return job.getIdempotencyKey() + "-" + job.getAttemptCount();
    }

    @Transactional
    public void failed(String agentCode, String agentToken, Long queueEntryId, String reason, String key) {
        PrintAgent agent = authenticate(agentCode, agentToken);
        queueService.lockShop(agent.getShopId());
        QueueEntry entry = requireEntry(agent.getShopId(), queueEntryId);
        requireAssignment(agent, entry);
        requireAttempt(entry, key);
        if (QueueEntry.FAILED.equals(entry.getStatus())) return;
        requirePrinting(entry);
        String failure = reason == null || reason.isBlank()
            ? "The print agent reported a failure" : reason.trim();
        failEntry(entry, agent.getShopId(), failure);
    }

    private void failEntry(QueueEntry entry, long shopId, String reason) {
        for (PrintJob job : jobRepository.findByQueueEntryId(entry.getId())) {
            if (PrintJob.PRINTING.equals(job.getStatus())) {
                job.fail(reason);
                history.finished(job);
            }
        }
        queueService.markFailed(entry, shopId);
    }

    @Transactional
    public byte[] download(String agentCode, String agentToken, Long queueEntryId, Long fileId) {
        PrintAgent agent = authenticate(agentCode, agentToken);
        QueueEntry entry = requireEntry(agent.getShopId(), queueEntryId);
        requirePrinting(entry);
        jobRepository.findByQueueEntryIdAndOrderFileId(entry.getId(), fileId)
            .filter(job -> agent.getId().equals(job.getAgentId()) && PrintJob.PRINTING.equals(job.getStatus()))
            .orElseThrow(() -> new NotFoundException("Print file was not assigned to this agent"));
        Order order = orderRepository.findById(entry.getOrderId())
            .orElseThrow(() -> new NotFoundException("Queued order is missing"));
        return orderService.renderFile(order, fileId);
    }

    @Transactional(readOnly = true)
    public List<String> printerCodes(Long shopId) {
        return printerRepository.findByShopIdOrderByNameAsc(shopId).stream()
            .map(Printer::getPrinterCode).toList();
    }

    private Long pinned(ShopSettings settings, PrintColorMode colorMode) {
        return colorMode == PrintColorMode.COLOR
            ? firstNonNull(settings.getColorPrinterId(), settings.getDefaultPrinterId())
            : firstNonNull(settings.getBwPrinterId(), settings.getDefaultPrinterId());
    }

    private Long firstNonNull(Long first, Long second) {
        return first != null ? first : second;
    }

    private PrintAgent authenticate(String agentCode, String agentToken) {
        if (agentCode == null || agentCode.isBlank() || agentToken == null || agentToken.isBlank()) {
            throw new InvalidCredentialsException("Print agent credentials are required");
        }
        PrintAgent agent = agentRepository.findByAgentCode(agentCode.trim())
            .orElseThrow(() -> new InvalidCredentialsException("Unknown print agent"));
        if (!passwordEncoder.matches(agentToken, agent.getCredentialHash())) {
            throw new InvalidCredentialsException("Invalid print agent credentials");
        }
        return agent;
    }

    private QueueEntry requireEntry(Long shopId, Long entryId) {
        QueueEntry entry = queueEntryRepository.findById(entryId)
            .orElseThrow(() -> new NotFoundException("Queue entry " + entryId + " was not found"));
        if (!shopId.equals(entry.getShopId())) {
            throw new NotFoundException("Queue entry " + entryId + " was not found");
        }
        return entry;
    }

    private void requireAssignment(PrintAgent agent, QueueEntry entry) {
        if (!assignedTo(agent, jobRepository.findByQueueEntryId(entry.getId()))) {
            throw new NotFoundException("Print job was not assigned to this agent");
        }
    }

    private boolean assignedTo(PrintAgent agent, Collection<PrintJob> jobs) {
        var unfinished = jobs.stream().filter(job -> !PrintJob.DONE.equals(job.getStatus())).toList();
        if (!unfinished.isEmpty()) {
            return unfinished.stream().allMatch(job -> agent.getId().equals(job.getAgentId()));
        }
        return jobs.stream().filter(job -> job.getClaimedAt() != null)
            .max(Comparator.comparing(PrintJob::getClaimedAt))
            .map(job -> agent.getId().equals(job.getAgentId())).orElse(false);
    }

    private String assignmentKey(QueueEntry entry) {
        if (entry.getAssignmentToken() != null) return entry.getAssignmentToken().toString();
        return entry.getStartedAt() == null ? null : entry.getStartedAt().toString();
    }

    private void requireAttempt(QueueEntry entry, String key) {
        if (key != null && !Objects.equals(key, assignmentKey(entry))) {
            throw new IllegalStateException("This print assignment has changed");
        }
    }

    private void requirePrinting(QueueEntry entry) {
        if (!QueueEntry.PRINTING.equals(entry.getStatus())) {
            throw new IllegalStateException("The job is no longer printing");
        }
    }

    private PrinterDtos.PrinterView view(Printer printer, List<String> activeOrders, Set<String> unavailable) {
        String availability = PrinterSelection.availability(printer, Instant.now().minusSeconds(35));
        if ("FREE".equals(availability) && unavailable.contains(PrinterSelection.resource(printer))) {
            availability = "BUSY";
        }
        String connection = switch (availability) {
            case "OFFLINE" -> "OFFLINE";
            case "ERROR" -> "UNKNOWN";
            default -> "ONLINE";
        };
        return new PrinterDtos.PrinterView(
            printer.getId(), printer.getPrinterCode(), printer.getName(),
            printer.getConnectionType(), printer.getPrinterUri(), connection,
            printer.isEnabled(), printer.isColorSupported(), printer.isDuplexSupported(),
            printer.getLastSeenAt(), printer.getAgentId(), printer.isAgentDefault(), printer.isVirtualPrinter(), printer.getDeviceGroup(), printer.getModel(), printer.getPortName(),
            availability, printer.getPendingJobs(), printer.getActiveJobs(), activeOrders);
    }

    private String status(String reported, Instant seenAt) {
        return seenAt == null || seenAt.isBefore(Instant.now().minusSeconds(35))
            ? "OFFLINE" : reported;
    }

    private String token(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }
}
