package com.itemmanagement.repository;

import com.itemmanagement.entity.SalesDetail;
import com.itemmanagement.entity.enums.SaleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface SalesDetailRepository extends JpaRepository<SalesDetail, Long> {

    List<SalesDetail> findBySalesIdOrderByItemId(Long salesId);

    List<SalesDetail> findBySalesIdIn(List<Long> salesIds);

    void deleteBySalesId(Long salesId);

    long countByStoreIdAndTaxId(Long storeId, Long taxId);

    long countByStoreIdAndUnitIgnoreCase(Long storeId, String unit);

    @Query("""
            select sd from SalesDetail sd, Sale s
            where sd.salesId = s.id and sd.storeId = :storeId
              and s.status = :status and s.salesDate between :from and :to
              and (:branchId is null or s.branchId = :branchId)
              and (:branchIds is null or s.branchId in :branchIds)
            order by s.salesDate desc, sd.salesId desc
            """)
    List<SalesDetail> findForPeriod(@Param("storeId") Long storeId,
                                    @Param("branchIds") Collection<Long> branchIds,
                                    @Param("branchId") Long branchId,
                                    @Param("status") SaleStatus status,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);
}
