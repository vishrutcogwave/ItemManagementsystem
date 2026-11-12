package com.itemmanagement.repository;

import com.itemmanagement.entity.UnitMaster;
import com.itemmanagement.entity.enums.StoreStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UnitMasterRepository extends JpaRepository<UnitMaster, Long> {

    Optional<UnitMaster> findByIdAndStoreId(Long id, Long storeId);

    Optional<UnitMaster> findByStoreIdAndUnitCodeIgnoreCase(Long storeId, String unitCode);

    Optional<UnitMaster> findFirstByStoreIdAndUnitNameIgnoreCase(Long storeId, String unitName);

    List<UnitMaster> findByStoreIdOrderByUnitCodeAsc(Long storeId);

    boolean existsByStoreIdAndUnitCodeIgnoreCase(Long storeId, String unitCode);

    @Query("""
            select u from UnitMaster u
            where u.storeId = :storeId
              and (:status is null or u.status = :status)
              and (:search is null or lower(u.unitCode) like lower(concat('%', :search, '%'))
                   or lower(u.unitName) like lower(concat('%', :search, '%'))
                   or lower(coalesce(u.description, '')) like lower(concat('%', :search, '%')))
            order by u.unitCode
            """)
    Page<UnitMaster> search(@Param("storeId") Long storeId, @Param("search") String search,
                            @Param("status") StoreStatus status, Pageable pageable);
}
