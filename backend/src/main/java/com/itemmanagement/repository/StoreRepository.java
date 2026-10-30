package com.itemmanagement.repository;

import com.itemmanagement.entity.Store;
import com.itemmanagement.entity.enums.StoreStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    Optional<Store> findByStoreCodeIgnoreCase(String storeCode);

    boolean existsByStoreCodeIgnoreCase(String storeCode);

    List<Store> findByStatusOrderByStoreName(StoreStatus status);

    @Query("""
            select s from Store s
            where (:search is null or lower(s.storeName) like lower(concat('%', :search, '%'))
                or lower(s.storeCode) like lower(concat('%', :search, '%'))
                or lower(s.ownerName) like lower(concat('%', :search, '%'))
                or lower(s.email) like lower(concat('%', :search, '%')))
              and (:status is null or s.status = :status)
            order by s.createdAt desc
            """)
    Page<Store> search(@Param("search") String search, @Param("status") StoreStatus status, Pageable pageable);

    long countByStatus(StoreStatus status);
}
