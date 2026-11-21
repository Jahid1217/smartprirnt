package com.smartprint.printer;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {
    private final PrintDispatchService dispatchService;

    private final DevicePairingService pairing;

    public record PairingRequest(@jakarta.validation.constraints.NotNull
                                 @jakarta.validation.constraints.Pattern(regexp = "[0-9a-fA-F]{20}") String code) {}

    public AgentController(PrintDispatchService dispatchService, DevicePairingService pairing) {
        this.dispatchService = dispatchService;
        this.pairing = pairing;
    }

    @PostMapping("/pairing")
    public ResponseEntity<PrinterDtos.CreatedAgent> pair(@Valid @RequestBody PairingRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pairing.redeem(request.code()));
    }

    @PostMapping("/heartbeat")
    public PrinterDtos.AgentStatus heartbeat(
            @RequestHeader("X-Agent-Code") String agentCode,
            @RequestHeader("X-Agent-Token") String agentToken,
            @Valid @RequestBody PrinterDtos.HeartbeatRequest request) {
        return dispatchService.heartbeat(agentCode, agentToken, request.printers(), request.discoveryError(), request.platform());
    }

    @PostMapping("/jobs/assignments")
    public java.util.List<PrinterDtos.AgentJob> assignments(
            @RequestHeader("X-Agent-Code") String agentCode,
            @RequestHeader("X-Agent-Token") String agentToken) {
        return dispatchService.claimAssignments(agentCode, agentToken);
    }

    @PostMapping("/jobs/next")
    public ResponseEntity<PrinterDtos.AgentJob> next(
            @RequestHeader("X-Agent-Code") String agentCode,
            @RequestHeader("X-Agent-Token") String agentToken) {
        return dispatchService.claimNext(agentCode, agentToken)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NO_CONTENT).build());
    }

    @PostMapping("/jobs/{queueEntryId}/complete")
    public void complete(@RequestHeader("X-Agent-Code") String agentCode,
                         @RequestHeader("X-Agent-Token") String agentToken,
                         @PathVariable Long queueEntryId,
                         @RequestHeader(value = "X-Assignment-Key", required = false) String assignmentKey) {
        dispatchService.complete(agentCode, agentToken, queueEntryId, assignmentKey);
    }

    @PostMapping("/jobs/{queueEntryId}/files/{fileId}/spooler")
    public void spooler(@RequestHeader("X-Agent-Code") String agentCode,
                        @RequestHeader("X-Agent-Token") String agentToken,
                        @RequestHeader("X-Submission-Key") String key,
                        @PathVariable Long queueEntryId, @PathVariable Long fileId,
                        @Valid @RequestBody PrinterDtos.SpoolerUpdate update) {
        dispatchService.reportSpooler(agentCode, agentToken, queueEntryId, fileId, key, update);
    }

    @PostMapping("/jobs/{queueEntryId}/files/{fileId}/complete")
    public void completeFile(@RequestHeader("X-Agent-Code") String agentCode,
                             @RequestHeader("X-Agent-Token") String agentToken,
                             @RequestHeader("X-Submission-Key") String key,
                             @PathVariable Long queueEntryId, @PathVariable Long fileId) {
        dispatchService.completeFile(agentCode, agentToken, queueEntryId, fileId, key);
    }

    @PostMapping("/jobs/{queueEntryId}/failed")
    public void failed(@RequestHeader("X-Agent-Code") String agentCode,
                       @RequestHeader("X-Agent-Token") String agentToken,
                       @PathVariable Long queueEntryId,
                       @RequestHeader(value = "X-Assignment-Key", required = false) String assignmentKey,
                       @Valid @RequestBody(required = false) PrinterDtos.JobFailureRequest request) {
        dispatchService.failed(agentCode, agentToken, queueEntryId,
            request == null ? null : request.reason(), assignmentKey);
    }

    @GetMapping(value = "/jobs/{queueEntryId}/files/{fileId}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> download(
            @RequestHeader("X-Agent-Code") String agentCode,
            @RequestHeader("X-Agent-Token") String agentToken,
            @PathVariable Long queueEntryId,
            @PathVariable Long fileId) {
        byte[] pdf = dispatchService.download(agentCode, agentToken, queueEntryId, fileId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"print-job-" + fileId + ".pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf);
    }
}
