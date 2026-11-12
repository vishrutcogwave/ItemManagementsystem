package com.itemmanagement.entity;

import com.itemmanagement.entity.enums.ImportBatchStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "item_import_batches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long branchId;

    @Column(nullable = false, length = 50)
    private String importNumber;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 64)
    private String fileHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportBatchStatus status;

    @Column(nullable = false)
    private int totalRows;

    @Column(nullable = false)
    private int successRows;

    @Column(nullable = false)
    private int failedRows;

    @Column(nullable = false)
    private int newCategories;

    @Column(nullable = false)
    private int newUnits;

    @Column(nullable = false)
    private int reusedUnits;

    @Column(nullable = false)
    private int newItems;

    @Column(nullable = false)
    private int existingItems;

    @Column(nullable = false)
    private int stockUpdates;

    @Column(nullable = false)
    private int duplicateRows;

    @Column(nullable = false)
    private int invalidRows;

    @Column(nullable = false)
    private int alreadyImportedRows;

    @Column(length = 500)
    private String failureMessage;

    private Long uploadedBy;

    @Column(length = 80)
    private String uploadedByUsername;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant completedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = ImportBatchStatus.PREVIEWED;
    }
}
