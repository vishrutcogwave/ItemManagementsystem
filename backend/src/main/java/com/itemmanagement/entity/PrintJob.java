package com.itemmanagement.entity;

import com.itemmanagement.entity.enums.PrintDocumentType;
import com.itemmanagement.entity.enums.PrintJobChannel;
import com.itemmanagement.entity.enums.PrintJobStatus;
import com.itemmanagement.entity.enums.PrintJobTrigger;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "print_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrintJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long branchId;

    @Column(nullable = false, length = 150)
    private String jobKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PrintDocumentType documentType;

    private Long documentId;

    @Column(length = 60)
    private String documentNumber;

    private Long printerConfigId;

    @Column(length = 100)
    private String printerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PrintJobChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PrintJobTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PrintJobStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 500)
    private String errorMessage;

    private Long createdBy;

    @Column(length = 80)
    private String createdByUsername;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant printedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = PrintJobStatus.PENDING;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
