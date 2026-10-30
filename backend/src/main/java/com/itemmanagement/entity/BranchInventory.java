package com.itemmanagement.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "branch_inventory")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BranchInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long branchId;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal currentStock;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal minimumStock;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal purchasePrice;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal sellingPrice;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal avgCost;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
        if (currentStock == null) currentStock = BigDecimal.ZERO;
        if (avgCost == null) avgCost = BigDecimal.ZERO;
    }
}
