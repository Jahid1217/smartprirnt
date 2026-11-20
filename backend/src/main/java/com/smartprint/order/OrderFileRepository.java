package com.smartprint.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderFileRepository extends JpaRepository<OrderFile, Long> {
    List<OrderFile> findByOrderIdOrderByIdAsc(Long orderId);

    Optional<OrderFile> findByIdAndOrderId(Long id, Long orderId);

    long countByOrderId(Long orderId);
}
