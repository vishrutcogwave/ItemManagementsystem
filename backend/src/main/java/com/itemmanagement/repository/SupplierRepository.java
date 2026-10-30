package com.itemmanagement.repository;

import com.itemmanagement.entity.Supplier;
import com.itemmanagement.entity.enums.StoreStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    Optional<Supplier> findByIdAndStoreId(Long id, Long storeId);

    List<Supplier> findByStoreIdAndStatusOrderBySupplierName(Long storeId, StoreStatus status);

    List<Supplier> findByStoreIdOrderBySupplierName(Long storeId);

    long countByStoreId(Long storeId);

    @Query("""
            select s from Supplier s
            where s.storeId = :storeId
              and (:status is null or s.status = :status)
              and (:search is null or lower(s.supplierName) like lower(concat('%', :search, '%'))
                   or s.mobile like concat('%', :search, '%'))
            order by s.supplierName
            """)
    Page<Supplier> search(@Param("storeId") Long storeId, @Param("status") StoreStatus status,
                          @Param("search") String search, Pageable pageable);
}
