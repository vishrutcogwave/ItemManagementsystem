package com.itemmanagement.repository;

import com.itemmanagement.entity.SupplierPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long> {

    Optional<SupplierPayment> findByIdAndStoreId(Long id, Long storeId);

    Optional<SupplierPayment> findByStoreIdAndClientReference(Long storeId, String clientReference);

    List<SupplierPayment> findByStoreIdAndSupplierIdAndPaymentDateLessThanEqualOrderByPaymentDateAscIdAsc(
            Long storeId, Long supplierId, LocalDate to);

    List<SupplierPayment> findByStoreIdAndSupplierIdAndPaymentDateBetweenOrderByPaymentDateAscIdAsc(
            Long storeId, Long supplierId, LocalDate from, LocalDate to);

    @Query("""
            select p from SupplierPayment p
            where p.storeId = :storeId
              and (:supplierId is null or p.supplierId = :supplierId)
              and p.paymentDate >= :from
              and p.paymentDate <= :to
              and exists (
                    select 1 from SupplierPaymentAllocation a, Purchase pu
                    where a.paymentId = p.id and pu.id = a.purchaseId and pu.branchId in :branchIds)
              and (:search = '' or lower(p.paymentNumber) like lower(concat('%', :search, '%'))
                   or lower(coalesce(p.referenceNumber, '')) like lower(concat('%', :search, '%'))
                   or lower(coalesce(p.remarks, '')) like lower(concat('%', :search, '%'))
                   or exists (select 1 from Supplier s where s.id = p.supplierId
                              and lower(s.supplierName) like lower(concat('%', :search, '%'))))
            order by p.paymentDate desc, p.id desc
            """)
    Page<SupplierPayment> searchInBranches(@Param("storeId") Long storeId,
                                           @Param("supplierId") Long supplierId,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to,
                                           @Param("branchIds") Collection<Long> branchIds,
                                           @Param("search") String search,
                                           Pageable pageable);

    @Query("""
            select p from SupplierPayment p
            where p.storeId = :storeId
              and (:supplierId is null or p.supplierId = :supplierId)
              and p.paymentDate >= :from
              and p.paymentDate <= :to
              and (:search = '' or lower(p.paymentNumber) like lower(concat('%', :search, '%'))
                   or lower(coalesce(p.referenceNumber, '')) like lower(concat('%', :search, '%'))
                   or lower(coalesce(p.remarks, '')) like lower(concat('%', :search, '%'))
                   or exists (select 1 from Supplier s where s.id = p.supplierId
                              and lower(s.supplierName) like lower(concat('%', :search, '%'))))
            order by p.paymentDate desc, p.id desc
            """)
    Page<SupplierPayment> searchAllBranches(@Param("storeId") Long storeId,
                                            @Param("supplierId") Long supplierId,
                                            @Param("from") LocalDate from,
                                            @Param("to") LocalDate to,
                                            @Param("search") String search,
                                            Pageable pageable);
}
