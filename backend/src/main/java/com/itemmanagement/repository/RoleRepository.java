package com.itemmanagement.repository;

import com.itemmanagement.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByStoreIdAndCode(Long storeId, String code);

    Optional<Role> findByStoreIdIsNullAndCode(String code);

    Optional<Role> findByIdAndStoreId(Long id, Long storeId);

    List<Role> findByStoreIdOrderByName(Long storeId);

    boolean existsByStoreIdAndCode(Long storeId, String code);
}
