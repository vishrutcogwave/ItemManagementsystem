package com.itemmanagement.entity;

import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.entity.enums.TaxType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "tax_master")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false, length = 120)
    private String taxName;

    @Column(length = 255)
    private String description;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal gstPercentage;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal cgstPercentage;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal sgstPercentage;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal igstPercentage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaxType taxType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StoreStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) status = StoreStatus.ACTIVE;
        if (taxType == null) taxType = TaxType.GST;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
