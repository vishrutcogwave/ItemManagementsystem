package com.itemmanagement.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "sale_modifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaleModification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long saleId;

    @Column(nullable = false, length = 50)
    private String modificationNumber;

    @Column(nullable = false, updatable = false)
    private Instant modifiedAt;

    private Long modifiedBy;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal originalTotal;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal modifiedTotal;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal differenceAmount;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal originalQuantity;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal modifiedQuantity;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal quantityIncreased;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal quantityDecreased;

    @Column(nullable = false)
    private int itemsAdded;

    @Column(nullable = false)
    private int itemsRemoved;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal additionalPayable;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal refundDue;

    @PrePersist
    void onCreate() {
        if (modifiedAt == null) modifiedAt = Instant.now();
    }
}
