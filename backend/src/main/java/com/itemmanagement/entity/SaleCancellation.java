package com.itemmanagement.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "sale_cancellations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaleCancellation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long saleId;

    @Column(nullable = false, length = 50)
    private String cancellationNumber;

    @Column(nullable = false, length = 500)
    private String reason;

    private Long cancelledBy;

    @Column(nullable = false, updatable = false)
    private Instant cancelledAt;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal saleTotal;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal paidAmount;

    @Column(nullable = false)
    private int restoredItems;

    @PrePersist
    void onCreate() {
        if (cancelledAt == null) cancelledAt = Instant.now();
    }
}
