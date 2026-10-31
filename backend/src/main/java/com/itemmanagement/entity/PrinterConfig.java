package com.itemmanagement.entity;

import com.itemmanagement.entity.enums.PaperWidth;
import com.itemmanagement.entity.enums.PrinterPurpose;
import com.itemmanagement.entity.enums.StoreStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "printer_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrinterConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long branchId;

    @Column(nullable = false, length = 100)
    private String printerName;

    @Column(nullable = false, length = 120)
    private String ipAddress;

    @Column(nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private PaperWidth paperWidth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PrinterPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StoreStatus status;

    private Instant lastTestAt;

    @Column(length = 255)
    private String lastTestMessage;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = StoreStatus.ACTIVE;
        if (paperWidth == null) paperWidth = PaperWidth.MM80;
        if (purpose == null) purpose = PrinterPurpose.RECEIPT;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
