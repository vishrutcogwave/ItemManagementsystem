package com.itemmanagement.repository;

import com.itemmanagement.entity.PrintPairingCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PrintPairingCodeRepository extends JpaRepository<PrintPairingCode, Long> {

    Optional<PrintPairingCode> findByCodeHash(String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PrintPairingCode c where c.codeHash = :codeHash")
    Optional<PrintPairingCode> findForUpdateByCodeHash(@Param("codeHash") String codeHash);
}
