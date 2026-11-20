package com.smartprint.printer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrintAgentRepository extends JpaRepository<PrintAgent, Long> {
    Optional<PrintAgent> findByAgentCode(String agentCode);

    List<PrintAgent> findByShopIdOrderByCreatedAtAsc(Long shopId);
}
