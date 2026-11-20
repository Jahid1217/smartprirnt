package com.smartprint.printer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PrintJobRepository extends JpaRepository<PrintJob, Long> {
    List<PrintJob> findByQueueEntryId(Long queueEntryId);

    List<PrintJob> findByShopIdAndStatus(Long shopId, String status);

    boolean existsByQueueEntryId(Long queueEntryId);

    long countByShopIdAndStatus(Long shopId, String status);

    Optional<PrintJob> findByIdAndShopId(Long id, Long shopId);

    Optional<PrintJob> findByQueueEntryIdAndOrderFileId(Long queueEntryId, Long orderFileId);

    @Query("""
        select j from PrintJob j
        where j.shopId = :shopId and j.status = 'PENDING'
          and j.queueEntryId = :queueEntryId
        order by j.id asc
        """)
    List<PrintJob> findPendingForEntry(@Param("shopId") Long shopId,
                                       @Param("queueEntryId") Long queueEntryId);
}
