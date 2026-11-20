package com.smartprint.shop;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ShopRepository extends JpaRepository<Shop, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shop s where s.id = :id")
    Optional<Shop> lockById(@Param("id") Long id);

    Optional<Shop> findByShopCode(String shopCode);

    boolean existsByShopCode(String shopCode);

    Optional<Shop> findByQrToken(java.util.UUID qrToken);
}
