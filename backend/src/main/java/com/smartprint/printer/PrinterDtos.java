package com.smartprint.printer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class PrinterDtos {
    private PrinterDtos() {}

    public record PrinterView(
        Long id,
        String code,
        String name,
        String connectionType,
        String uri,
        String status,
        boolean enabled,
        boolean colorSupported,
        boolean duplexSupported,
        Instant lastSeenAt,
        Long agentId,
        boolean agentDefault,
        boolean virtualPrinter,
        String deviceGroup,
        String model,
        String portName,
        String availability,
        int pendingJobs,
        int activeJobs,
        List<String> activeOrders
    ) {}

    public record AgentView(
        Long id,
        String agentCode,
        String name,
        String status,
        Instant lastSeenAt,
        int printerCount,
        String discoveryError,
        String platform,
        Instant createdAt
    ) {}

    public record CreateAgentRequest(@NotBlank @Size(max = 150) String name) {}

    public record CreatedAgent(String agentCode, String agentToken, String name) {}

    public record PrinterReport(
        @NotBlank @Size(max = 59) String code,
        @NotBlank @Size(max = 512) String name,
        @NotBlank @Size(max = 20) String connectionType,
        @NotBlank @Size(max = 4096) String uri,
        @Size(max = 20) String status,
        boolean colorSupported,
        boolean duplexSupported,
        boolean agentDefault,
        boolean virtualPrinter,
        @Size(max = 64) String deviceGroup,
        @Size(max = 200) String model,
        @Size(max = 512) String portName,
        @jakarta.validation.constraints.Min(0) int pendingJobs,
        @jakarta.validation.constraints.Min(0) int activeJobs
    ) {}

    public record HeartbeatRequest(@Valid List<PrinterReport> printers,
                                   @Size(max = 200) String discoveryError,
                                   @Size(max = 20) String platform) {}

    public record Routing(Long defaultPrinterId, Long colorPrinterId, Long bwPrinterId) {}

    public record Discovery(List<AgentView> agents, List<PrinterView> printers, Routing routing, long queuedJobs, boolean queuePaused) {}

    public record AgentStatus(
        Long agentId,
        Long shopId,
        String shopName,
        String agentStatus,
        boolean queuePaused,
        int printerCount
    ) {}

    public record AgentFile(
        Long fileId,
        String submissionKey,
        String name,
        int copies,
        String colorMode,
        String sideMode,
        String orientation,
        String pageSelection,
        int selectedPages,
        Long printerId,
        String printerName,
        String printerUri,
        String downloadPath
    ) {}

    public record AgentJob(
        Long queueEntryId,
        Integer queueNumber,
        String orderNumber,
        String customerName,
        String assignmentKey,
        List<AgentFile> files
    ) {}

    public record SpoolerUpdate(
        @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[0-9]{1,20}") String reference,
        @NotBlank @jakarta.validation.constraints.Pattern(
            regexp = "QUEUED|PRINTING|PAUSED|COMPLETED|FAILED|CANCELLED|UNKNOWN") String status
    ) {}

    public record JobFailureRequest(@Size(max = 500) String reason) {}
}
