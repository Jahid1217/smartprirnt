package com.smartprint.printer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrinterRepository extends JpaRepository<Printer, Long> {
    List<Printer> findByShopIdOrderByNameAsc(Long shopId);

    List<Printer> findByShopIdAndEnabledTrueOrderByNameAsc(Long shopId);

    Optional<Printer> findByShopIdAndPrinterCode(Long shopId, String printerCode);

    Optional<Printer> findByIdAndShopId(Long id, Long shopId);
}
