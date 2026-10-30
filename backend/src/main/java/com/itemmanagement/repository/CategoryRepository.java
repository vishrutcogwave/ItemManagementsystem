package com.itemmanagement.repository;

import com.itemmanagement.entity.Category;
import com.itemmanagement.entity.enums.StoreStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByIdAndStoreId(Long id, Long storeId);

    List<Category> findByStoreIdAndStatusOrderByCategoryName(Long storeId, StoreStatus status);

    List<Category> findByStoreIdOrderByCategoryName(Long storeId);

    long countByStoreId(Long storeId);

    boolean existsByStoreIdAndCategoryNameIgnoreCase(Long storeId, String name);

    @Query("""
            select c from Category c
            where c.storeId = :storeId
              and (:search is null or lower(c.categoryName) like lower(concat('%', :search, '%'))
                   or lower(coalesce(c.description, '')) like lower(concat('%', :search, '%')))
              and (:status is null or c.status = :status)
            order by c.categoryName
            """)
    Page<Category> search(@Param("storeId") Long storeId, @Param("search") String search,
                          @Param("status") StoreStatus status, Pageable pageable);
}
