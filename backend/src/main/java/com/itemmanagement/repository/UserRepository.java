package com.itemmanagement.repository;

import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByStoreIdAndUsernameIgnoreCase(Long storeId, String username);

    Optional<User> findByStoreIdAndEmailIgnoreCase(Long storeId, String email);

    Optional<User> findByUsernameIgnoreCaseAndStoreIdIsNull(String username);

    Optional<User> findByEmailIgnoreCaseAndStoreIdIsNull(String email);

    Optional<User> findByIdAndStoreId(Long id, Long storeId);

    List<User> findByStoreIdOrderByFullName(Long storeId);

    boolean existsByStoreIdAndUsernameIgnoreCase(Long storeId, String username);

    long countByStoreId(Long storeId);

    @Query("""
            select u from User u
            where (:storeId is null or u.storeId = :storeId)
              and (:search is null or lower(u.fullName) like lower(concat('%', :search, '%'))
                or lower(u.username) like lower(concat('%', :search, '%'))
                or lower(u.email) like lower(concat('%', :search, '%')))
              and (:status is null or u.status = :status)
            order by u.fullName
            """)
    Page<User> search(@Param("storeId") Long storeId, @Param("search") String search,
                      @Param("status") StoreStatus status, Pageable pageable);

    @Query("select count(u) from User u join u.roles r where u.storeId = :storeId and r.code = :roleCode")
    long countByStoreIdAndRoleCode(@Param("storeId") Long storeId, @Param("roleCode") String roleCode);

    @Query("""
            select distinct u from User u
            join fetch u.roles r
            where u.storeId is null and r.storeId is null and r.code = 'PLATFORM_ADMIN'
            order by u.id
            """)
    List<User> findPlatformAdmins();
}
