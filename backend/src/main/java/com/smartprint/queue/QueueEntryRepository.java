package com.smartprint.queue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface QueueEntryRepository extends JpaRepository<QueueEntry, Long> {
    Optional<QueueEntry> findByOrderId(Long orderId);

    List<QueueEntry> findByOrderIdIn(Collection<Long> orderIds);

    long countByShopIdAndStatus(Long shopId, String status);

    long countByShopIdAndQueueDate(Long shopId, LocalDate queueDate);

    @Query("""
        select count(q) from QueueEntry q
        where q.shopId = :shopId
          and q.status = 'QUEUED' and coalesce(q.sortPriority, q.id) < :priority
        """)
    long countAhead(@Param("shopId") Long shopId, @Param("priority") Long priority);

    @Query("""
        select q from QueueEntry q
        where q.shopId = :shopId and q.status in ('QUEUED','PRINTING')
        order by case when q.status = 'PRINTING' then 0 else 1 end,
                 coalesce(q.sortPriority, q.id) asc
        """)
    List<QueueEntry> findActiveForShop(@Param("shopId") Long shopId);
}
