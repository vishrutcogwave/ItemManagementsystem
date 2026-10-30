package com.itemmanagement.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "sales_details")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long salesId;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, length = 20)
    private String unit;

    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal baseQuantity;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal sellingRate;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal costRate;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal discount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal tax;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private Long taxId;

    @Column(nullable = false, length = 120)
    private String taxNameSnapshot;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal gstPercentage;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal cgstPercentage;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal sgstPercentage;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal igstPercentage;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal taxableAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal cgstAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal sgstAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal igstAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal taxAmount;
}
