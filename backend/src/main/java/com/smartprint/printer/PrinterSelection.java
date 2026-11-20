package com.smartprint.printer;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class PrinterSelection {
    private PrinterSelection() {}

    static String availability(Printer printer, Instant cutoff) {
        if (printer.getLastSeenAt() == null || printer.getLastSeenAt().isBefore(cutoff)) return "OFFLINE";
        return switch (printer.getStatus()) {
            case "FREE" -> printer.getActiveJobs() == 0 ? "FREE" : "BUSY";
            case "BUSY", "OFFLINE", "ERROR", "PAUSED" -> printer.getStatus();
            default -> "ERROR"; // An older agent's ONLINE does not establish availability.
        };
    }

    static String resource(Printer printer) {
        return printer.getDeviceGroup() == null ? "printer:" + printer.getId()
            : "device:" + printer.getAgentId() + ":" + printer.getDeviceGroup();
    }

    static Printer select(List<Printer> printers, Set<String> unavailable, long agentId, Long pinnedId,
                          boolean color, boolean duplex, Instant cutoff) {
        return printers.stream()
            .filter(p -> Objects.equals(p.getAgentId(), agentId) && p.isEnabled())
            .filter(p -> pinnedId == null ? !p.isVirtualPrinter() : pinnedId.equals(p.getId()))
            .filter(p -> !color || p.isColorSupported())
            .filter(p -> !duplex || p.isDuplexSupported())
            .filter(p -> "FREE".equals(availability(p, cutoff)) && !unavailable.contains(resource(p)))
            .min(Comparator.comparingInt(Printer::getPendingJobs)
                .thenComparing(Printer::getLastAssignedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Printer::isAgentDefault, Comparator.reverseOrder())
                .thenComparing(Printer::getId)).orElse(null);
    }
}
