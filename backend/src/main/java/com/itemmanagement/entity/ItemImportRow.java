package com.itemmanagement.entity;

import com.itemmanagement.entity.enums.ImportAction;
import com.itemmanagement.entity.enums.ImportValidationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "item_import_rows")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemImportRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long batchId;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private int rowNumber;

    @Column(length = 50)
    private String itemCode;

    @Column(length = 150)
    private String itemName;

    @Column(length = 120)
    private String categoryName;

    @Column(length = 20)
    private String unit;

    @Column(precision = 14, scale = 2)
    private BigDecimal rate;

    @Column(length = 120)
    private String taxName;

    @Column(precision = 18, scale = 3)
    private BigDecimal openingStock;

    @Column(precision = 18, scale = 3)
    private BigDecimal minimumStock;

    @Column(length = 255)
    private String description;

    private Long itemId;

    @Column(nullable = false)
    private boolean parseFailed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ImportAction importAction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportValidationStatus validationStatus;

    @Column(length = 500)
    private String errorMessage;
}
