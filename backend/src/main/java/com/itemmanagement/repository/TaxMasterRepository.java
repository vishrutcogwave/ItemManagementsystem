package com.itemmanagement.repository;

import com.itemmanagement.entity.TaxMaster;
import com.itemmanagement.entity.enums.StoreStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaxMasterRepository extends JpaRepository<TaxMaster, Long> {

    Optional<TaxMaster> findByIdAndStoreId(Long id, Long storeId);

    List<TaxMaster> findByStoreIdOrderByGstPercentageAscTaxNameAsc(Long storeId);

    List<TaxMaster> findByStoreIdAndStatusOrderByGstPercentageAscTaxNameAsc(Long storeId, StoreStatus status);

    Optional<TaxMaster> findFirstByStoreIdAndTaxTypeOrderByGstPercentageAsc(Long storeId,
                                                                            com.itemmanagement.entity.enums.TaxType taxType);

    boolean existsByStoreIdAndTaxNameIgnoreCase(Long storeId, String taxName);

    long countByStoreId(Long storeId);

    @Query("""
            select t from TaxMaster t
            where t.storeId = :storeId
              and (:status is null or t.status = :status)
              and (:search is null or lower(t.taxName) like lower(concat('%', :search, '%'))
                   or lower(coalesce(t.description, '')) like lower(concat('%', :search, '%')))
            order by t.gstPercentage, t.taxName
            """)
    Page<TaxMaster> search(@Param("storeId") Long storeId, @Param("search") String search,
                           @Param("status") StoreStatus status, Pageable pageable);
}
