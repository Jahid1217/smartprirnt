package com.smartprint.queue;

import com.smartprint.auth.AdminPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/queue")
public class AdminQueueController {
    private final QueueService queueService;

    public AdminQueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @GetMapping
    public List<QueueDtos.QueueItem> queue(@AuthenticationPrincipal AdminPrincipal principal) {
        return queueService.activeQueue(principal.shopId());
    }

    @GetMapping("/summary")
    public QueueDtos.QueueSummary summary(@AuthenticationPrincipal AdminPrincipal principal) {
        return queueService.summary(principal.shopId());
    }

    @PostMapping("/pause")
    public QueueDtos.QueueSummary pause(@AuthenticationPrincipal AdminPrincipal principal,
                                        @RequestBody QueueDtos.PauseRequest request) {
        return queueService.setPaused(principal.shopId(), request.paused());
    }

    @PostMapping("/{entryId}/move")
    public void move(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable Long entryId,
                     @Valid @RequestBody QueueDtos.MoveRequest request) {
        queueService.move(principal.shopId(), entryId, request.direction());
    }

    @PostMapping("/{entryId}/cancel")
    public void cancel(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable Long entryId) {
        queueService.cancel(principal.shopId(), entryId);
    }

    @PostMapping("/{entryId}/retry")
    public void retry(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable Long entryId) {
        queueService.retry(principal.shopId(), entryId);
    }

    @PostMapping("/{entryId}/complete")
    public void complete(@AuthenticationPrincipal AdminPrincipal principal, @PathVariable Long entryId) {
        queueService.completeManually(principal.shopId(), entryId);
    }
}
