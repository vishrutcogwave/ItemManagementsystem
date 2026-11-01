package com.itemmanagement.repository;

import com.itemmanagement.entity.RevokedToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, String> {

    @Query("select t.expiresAt from RevokedToken t where t.jti = :jti")
    Instant findExpiry(@Param("jti") String jti);

    @Modifying
    @Query("delete from RevokedToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
