package com.itemmanagement.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "subscription_change_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionChangeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    private Long changedBy;

    @Column(length = 80)
    private String changedByUsername;

    @Column(nullable = false, updatable = false)
    private Instant changedAt;

    private java.time.LocalDate previousStartDate;
    private java.time.LocalDate newStartDate;
    private java.time.LocalDate previousEndDate;
    private java.time.LocalDate newEndDate;
    private Integer previousMaxBranches;
    private Integer newMaxBranches;

    @Column(length = 255)
    private String note;

    @PrePersist
    void onCreate() {
        if (changedAt == null) changedAt = Instant.now();
    }
}
