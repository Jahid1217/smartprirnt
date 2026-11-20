package com.smartprint.printer;

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
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrintDispatchServiceTest {
    @Test
    void batchResumesOwnedJobsAndFillsOnlyFreePrinterSlots() {
        var agents = mock(PrintAgentRepository.class);
        var printers = mock(PrinterRepository.class);
        var jobs = mock(PrintJobRepository.class);
        var orders = mock(OrderRepository.class);
        var files = mock(OrderFileRepository.class);
        var configs = mock(PrintConfigRepository.class);
        var queues = mock(QueueService.class);
        var entries = mock(QueueEntryRepository.class);
        var shops = mock(ShopService.class);
        var settings = mock(ShopSettingsService.class);
        var encoder = mock(PasswordEncoder.class);
        var history = mock(PrintAttemptHistory.class);
        var dispatch = new PrintDispatchService(agents, printers, jobs, orders, files, configs,
            mock(OrderService.class), queues, entries, shops, settings, mock(RealtimeService.class),
            encoder, history);

        var agent = new PrintAgent(1L, "agent", "hash", "Station");
        ReflectionTestUtils.setField(agent, "id", 1L);
        when(agents.findByAgentCode("agent")).thenReturn(Optional.of(agent));
        when(encoder.matches("token", "hash")).thenReturn(true);
        var shop = mock(Shop.class);
        when(shops.requireById(1L)).thenReturn(shop);
        var route = ShopSettings.defaults(1L);
        when(settings.settingsFor(shop)).thenReturn(route);
        when(printers.findByShopIdOrderByNameAsc(1L)).thenReturn(List.of(
            printer(1, "BUSY"), printer(2, "FREE"), printer(3, "FREE")));

        List<QueueEntry> queue = new ArrayList<>();
        List<PrintJob> reservations = new ArrayList<>();
        for (long id = 1; id <= 3; id++) {
            var entry = new QueueEntry(1L, id, LocalDate.now(), (int) id);
            ReflectionTestUtils.setField(entry, "id", id);
            queue.add(entry);
            var order = mock(Order.class);
            when(order.getId()).thenReturn(id);
            when(order.getOrderNumber()).thenReturn("ORDER-" + id);
            when(orders.findById(id)).thenReturn(Optional.of(order));
            var file = mock(OrderFile.class);
            when(file.getId()).thenReturn(id);
            when(files.findByOrderIdOrderByIdAsc(id)).thenReturn(List.of(file));
            var config = mock(PrintConfig.class);
            when(config.getOrderFileId()).thenReturn(id);
            when(config.getColorMode()).thenReturn(PrintColorMode.BW);
            when(config.getSideMode()).thenReturn(PrintSideMode.SIMPLEX);
            when(config.getOrientation()).thenReturn(PrintConfig.Orientation.PORTRAIT);
            when(configs.findByOrderId(id)).thenReturn(List.of(config));
        }
        when(entries.findActiveForShop(1L)).thenAnswer(invocation -> queue.stream()
            .filter(entry -> !QueueEntry.COMPLETED.equals(entry.getStatus())).toList());
        when(jobs.findByShopIdAndStatus(1L, PrintJob.PRINTING)).thenAnswer(invocation -> reservations
            .stream().filter(job -> PrintJob.PRINTING.equals(job.getStatus())).toList());
        when(jobs.findByQueueEntryId(anyLong())).thenAnswer(invocation -> reservations.stream()
            .filter(job -> job.getQueueEntryId().equals(invocation.getArgument(0))).toList());
        when(jobs.saveAndFlush(any(PrintJob.class))).thenAnswer(invocation -> {
            PrintJob job = invocation.getArgument(0);
            reservations.add(job);
            return job;
        });
        doAnswer(invocation -> {
            invocation.getArgument(0, QueueEntry.class).markPrinting();
            return null;
        }).when(queues).markPrinting(any(QueueEntry.class), anyLong());

        var batch = dispatch.claimAssignments("agent", "token");
        assertThat(batch.stream().map(assignment -> assignment.files().getFirst().printerId()))
            .containsExactly(2L, 3L);
        assertThat(queue.getLast().getStatus()).isEqualTo(QueueEntry.QUEUED);
        assertThat(dispatch.claimAssignments("agent", "token")).isEqualTo(batch);
        verify(history, times(2)).started(any(PrintJob.class));

        reservations.getFirst().done();
        queue.getFirst().markCompleted();
        var next = dispatch.claimAssignments("agent", "token");
        assertThat(next).hasSize(2);
        assertThat(next.getLast().queueEntryId()).isEqualTo(3L);
        assertThat(next.getLast().files().getFirst().printerId()).isEqualTo(2L);
        verify(history, times(3)).started(any(PrintJob.class));
    }

    private Printer printer(long id, String status) {
        var printer = new Printer(1L, 1L, "p" + id, "Printer " + id, "CUPS", "queue:" + id);
        ReflectionTestUtils.setField(printer, "id", id);
        printer.updateFromAgent(1L, printer.getName(), "CUPS", printer.getPrinterUri(), status, true, true);
        return printer;
    }
}
