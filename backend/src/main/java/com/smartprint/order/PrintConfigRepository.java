package com.smartprint.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrintConfigRepository extends JpaRepository<PrintConfig, Long> {
    List<PrintConfig> findByOrderId(Long orderId);

    Optional<PrintConfig> findByOrderFileId(Long orderFileId);
}
