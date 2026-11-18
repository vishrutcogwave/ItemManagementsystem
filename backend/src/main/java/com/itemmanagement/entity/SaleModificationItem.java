package com.itemmanagement.entity;

import com.itemmanagement.entity.enums.ModificationChangeType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "sale_modification_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaleModificationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long modificationId;

    @Column(nullable = false)
    private Long itemId;

    @Column(length = 120)
    private String itemCode;

    @Column(length = 200)
    private String itemName;

    @Column(length = 20)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ModificationChangeType changeType;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal originalQuantity;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal modifiedQuantity;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal originalRate;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal modifiedRate;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal originalAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal modifiedAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal differenceAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal taxAmount;
}
