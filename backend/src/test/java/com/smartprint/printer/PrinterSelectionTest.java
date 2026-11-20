package com.smartprint.printer;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PrinterSelectionTest {
    private final Instant cutoff = Instant.now().minusSeconds(35);

    @Test
    void theLeastLoadedFreePrinterWinsAndTiesRotate() {
        Printer busy = printer(1, "BUSY", 2, 0, true, true);
        Printer first = printer(2, "FREE", 0, 0, true, true);
        Printer second = printer(3, "FREE", 0, 0, true, true);
        Printer pending = printer(4, "FREE", 1, 0, true, true);
        var printers = List.of(busy, first, second, pending);

        assertThat(select(printers, Set.of(), null, true)).isSameAs(first);
        first.markAssigned(Instant.now());
        assertThat(select(printers, Set.of(), null, true)).isSameAs(second);
        assertThat(select(printers, Set.of(PrinterSelection.resource(second)), null, true))
            .isSameAs(first);
    }

    @Test
    void unsafeOrIncompatibleCandidatesAreExcludedEvenWhenPinned() {
        Printer erroneous = printer(1, "ERROR", 0, 0, true, true);
        Printer offline = printer(2, "OFFLINE", 0, 0, true, true);
        Printer paused = printer(3, "PAUSED", 0, 0, true, true);
        Printer printing = printer(4, "FREE", 0, 1, true, true);
        Printer monochrome = printer(5, "FREE", 0, 0, false, true);
        Printer stale = printer(6, "FREE", 0, 0, true, true);
        ReflectionTestUtils.setField(stale, "lastSeenAt", cutoff.minusSeconds(1));
        Printer virtual = printer(7, "FREE", 0, 0, true, true);
        virtual.setDeviceMetadata(true, null);
        Printer legacy = printer(8, "ONLINE", 0, 0, true, true);
        var printers = List.of(erroneous, offline, paused, printing, monochrome, stale, virtual, legacy);

        assertThat(select(printers, Set.of(), null, true)).isNull();
        assertThat(select(printers, Set.of(), 1L, false)).isNull();
        assertThat(select(printers, Set.of(), null, false)).isSameAs(monochrome);
    }

    @Test
    void aReservedPhysicalPrinterCannotBeSelectedThroughAnotherAlias() {
        Printer first = printer(1, "FREE", 0, 0, true, true);
        Printer alias = printer(2, "FREE", 0, 0, true, true);
        first.setDeviceMetadata(false, "same-port");
        alias.setDeviceMetadata(false, "same-port");
        assertThat(select(List.of(alias), Set.of(PrinterSelection.resource(first)), 2L, false)).isNull();
    }

    private Printer select(List<Printer> printers, Set<String> reservations, Long pinned, boolean color) {
        return PrinterSelection.select(printers, reservations, 1L, pinned, color, true, cutoff);
    }

    private Printer printer(long id, String status, int pending, int active, boolean color, boolean duplex) {
        Printer printer = new Printer(1L, 1L, "p" + id, "Test printer", "WINDOWS", "queue:" + id);
        ReflectionTestUtils.setField(printer, "id", id);
        printer.updateFromAgent(1L, printer.getName(), "WINDOWS", printer.getPrinterUri(), status, color, duplex);
        printer.reportWorkload(null, pending, active);
        return printer;
    }
}
