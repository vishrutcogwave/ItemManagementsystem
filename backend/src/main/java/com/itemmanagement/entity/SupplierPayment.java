package com.itemmanagement.entity;

import com.itemmanagement.entity.enums.SupplierPaymentMethod;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "supplier_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplierPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long supplierId;

    @Column(nullable = false, length = 50)
    private String paymentNumber;

    @Column(nullable = false)
    private LocalDate paymentDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplierPaymentMethod paymentMethod;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(length = 80)
    private String referenceNumber;

    @Column(length = 500)
    private String remarks;

    @Column(length = 80)
    private String clientReference;

    private Long createdBy;

    @Column(length = 150)
    private String createdByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
