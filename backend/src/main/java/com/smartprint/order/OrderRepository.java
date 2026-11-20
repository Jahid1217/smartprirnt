package com.smartprint.order;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderNumber(String orderNumber);

    Optional<Order> findByPublicToken(java.util.UUID publicToken);

    Optional<Order> findByOrderNumberAndShopId(String orderNumber, Long shopId);

    Optional<Order> findByIdAndShopId(Long id, Long shopId);

    Optional<Order> findByShopIdAndRequestKey(Long shopId, java.util.UUID requestKey);

    boolean existsByOrderNumber(String orderNumber);

    List<Order> findTop10ByShopIdOrderByCreatedAtDesc(Long shopId);

    long countByShopIdAndCreatedAtGreaterThanEqual(Long shopId, Instant from);

    long countByShopIdAndCreatedAtBetween(Long shopId, Instant from, Instant to);

    long countByShopIdAndStatusAndCreatedAtBetween(Long shopId, OrderStatus status,
                                                   Instant from, Instant to);

    List<Order> findByShopIdAndStatusInOrderByCreatedAtAsc(Long shopId, Collection<String> statuses);

    List<Order> findByStatusInAndPurgeAtBefore(Collection<String> statuses, Instant cutoff);

    @Query("select o.id from Order o where o.purgeAt <= :cutoff order by o.purgeAt, o.id")
    List<Long> findDueForPurge(@Param("cutoff") Instant cutoff, Pageable page);

    @Query("select o.shopId from Order o where o.id = :id")
    Optional<Long> findShopId(@Param("id") Long id);

    @Query("""
        select o from Order o
        where o.shopId = :shopId
          and (:status is null or o.status = :status)
        order by o.createdAt desc, o.id desc
        """)
    List<Order> findRecentForShop(@Param("shopId") Long shopId,
                                  @Param("status") OrderStatus status,
                                  Pageable pageable);
}
