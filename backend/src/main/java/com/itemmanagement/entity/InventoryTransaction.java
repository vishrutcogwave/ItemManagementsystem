package com.itemmanagement.entity;

import com.itemmanagement.entity.enums.TransactionType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "inventory_transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long branchId;

    @Column(nullable = false)
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransactionType transactionType;

    @Column(length = 30)
    private String referenceType;

    private Long referenceId;

    @Column(length = 50)
    private String referenceNumber;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal quantityIn;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal quantityOut;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal balanceAfter;

    @Column(length = 255)
    private String notes;

    @Column(nullable = false)
    private Instant transactionDate;

    private Long createdBy;

    @PrePersist
    void onCreate() {
        if (transactionDate == null) transactionDate = Instant.now();
        if (quantityIn == null) quantityIn = BigDecimal.ZERO;
        if (quantityOut == null) quantityOut = BigDecimal.ZERO;
    }
}
