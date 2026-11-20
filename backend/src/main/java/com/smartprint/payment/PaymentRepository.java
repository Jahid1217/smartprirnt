package com.smartprint.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> lockByOrderId(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") Long id);

    boolean existsByProviderAndTransactionId(String provider, String transactionId);

    // Native queries: "order" is a JPQL reserved word, so entity joins are avoided here.
    @Query(value = """
        select p.* from payments p
        join orders o on o.id = p.order_id
        where o.shop_id = :shopId and o.status = 'PAYMENT_PENDING' and p.status in (:statuses)
        order by p.created_at asc
        """, nativeQuery = true)
    List<Payment> findForShopByStatuses(@Param("shopId") Long shopId,
                                        @Param("statuses") List<String> statuses);

    @Query(value = """
        select count(*) from payments p
        join orders o on o.id = p.order_id
        where o.shop_id = :shopId and o.status = 'PAYMENT_PENDING' and p.status in (:statuses)
        """, nativeQuery = true)
    long countForShopByStatuses(@Param("shopId") Long shopId,
                                @Param("statuses") List<String> statuses);

    @Query(value = """
        select coalesce(sum(p.amount), 0) from payments p
        join orders o on o.id = p.order_id
        where o.shop_id = :shopId and p.status = 'APPROVED'
          and p.verified_at >= :from and p.verified_at < :to
        """, nativeQuery = true)
    BigDecimal sumApprovedForShopBetween(@Param("shopId") Long shopId,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);
}
