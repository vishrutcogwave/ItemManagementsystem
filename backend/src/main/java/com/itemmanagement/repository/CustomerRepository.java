package com.itemmanagement.repository;

import com.itemmanagement.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findFirstByStoreIdAndMobileOrderByIdDesc(Long storeId, String mobile);
}
