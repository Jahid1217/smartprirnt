package com.smartprint.queue;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface QueueCounterRepository extends JpaRepository<QueueCounter, Long> {
    Optional<QueueCounter> findByShopIdAndQueueDate(Long shopId, LocalDate queueDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QueueCounter q where q.shopId = :shopId and q.queueDate = :queueDate")
    Optional<QueueCounter> findLocked(@Param("shopId") Long shopId,
                                      @Param("queueDate") LocalDate queueDate);
}
