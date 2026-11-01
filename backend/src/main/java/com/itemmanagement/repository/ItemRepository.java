package com.itemmanagement.repository;

import com.itemmanagement.entity.Item;
import com.itemmanagement.entity.enums.StoreStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long> {

    Optional<Item> findByIdAndStoreId(Long id, Long storeId);

    List<Item> findByStoreIdOrderByItemName(Long storeId);

    List<Item> findByStoreIdAndStatusOrderByItemName(Long storeId, StoreStatus status);

    List<Item> findByStoreIdAndCategoryIdOrderByItemName(Long storeId, Long categoryId);

    long countByStoreId(Long storeId);

    long countByStoreIdAndStatus(Long storeId, StoreStatus status);

    long countByStoreIdAndCategoryId(Long storeId, Long categoryId);

    long countByStoreIdAndTaxId(Long storeId, Long taxId);

    boolean existsByStoreIdAndItemCodeIgnoreCase(Long storeId, String itemCode);

    Optional<Item> findByStoreIdAndItemCodeIgnoreCase(Long storeId, String itemCode);

    Optional<Item> findByStoreIdAndBarcode(Long storeId, String barcode);

    boolean existsByStoreIdAndBarcode(Long storeId, String barcode);

    List<Item> findByStoreIdAndItemNameIgnoreCaseAndCategoryId(Long storeId, String itemName, Long categoryId);

    @Query("""
            select i from Item i
            where i.storeId = :storeId
              and (:categoryId is null or i.categoryId = :categoryId)
              and (:status is null or i.status = :status)
              and (:search is null or lower(i.itemName) like lower(concat('%', :search, '%'))
                   or lower(i.itemCode) like lower(concat('%', :search, '%'))
                   or lower(i.barcode) like lower(concat('%', :search, '%')))
            order by i.itemName
            """)
    Page<Item> search(@Param("storeId") Long storeId, @Param("categoryId") Long categoryId,
                      @Param("status") StoreStatus status, @Param("search") String search, Pageable pageable);
}
