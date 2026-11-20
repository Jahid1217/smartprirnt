package com.smartprint.printer;

import com.smartprint.auth.AdminPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminPrinterController {
    public record EnabledRequest(boolean enabled) {}

    private final PrintDispatchService dispatchService;

    private final PrintAttemptHistory history;

    public AdminPrinterController(PrintDispatchService dispatchService, PrintAttemptHistory history) {
        this.history = history;
        this.dispatchService = dispatchService;
    }

    @GetMapping("/orders/{orderNumber}/print-attempts")
    public List<java.util.Map<String, Object>> attempts(@AuthenticationPrincipal AdminPrincipal principal,
                                                       @PathVariable String orderNumber) {
        return history.forOrder(principal.shopId(), orderNumber);
    }

    @GetMapping("/printers/discovery")
    public PrinterDtos.Discovery discovery(@AuthenticationPrincipal AdminPrincipal principal) {
        return dispatchService.discovery(principal.shopId());
    }

    @PatchMapping("/printers/routing")
    public PrinterDtos.Routing routing(@AuthenticationPrincipal AdminPrincipal principal,
                                      @RequestBody PrinterDtos.Routing request) {
        return dispatchService.saveRouting(principal.shopId(), request);
    }

    @GetMapping("/printers")
    public List<PrinterDtos.PrinterView> printers(@AuthenticationPrincipal AdminPrincipal principal) {
        return dispatchService.printers(principal.shopId());
    }

    @PatchMapping("/printers/{printerId}")
    public PrinterDtos.PrinterView setEnabled(@AuthenticationPrincipal AdminPrincipal principal,
                                              @PathVariable Long printerId,
                                              @RequestBody EnabledRequest request) {
        return dispatchService.setEnabled(principal.shopId(), printerId, request.enabled());
    }

    @GetMapping("/agents")
    public List<PrinterDtos.AgentView> agents(@AuthenticationPrincipal AdminPrincipal principal) {
        return dispatchService.agents(principal.shopId());
    }

    @PostMapping("/agents")
    public ResponseEntity<PrinterDtos.CreatedAgent> createAgent(
            @AuthenticationPrincipal AdminPrincipal principal,
            @Valid @RequestBody PrinterDtos.CreateAgentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(dispatchService.createAgent(principal.shopId(), request.name()));
    }
}
