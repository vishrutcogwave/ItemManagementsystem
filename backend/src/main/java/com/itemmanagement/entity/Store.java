package com.itemmanagement.entity;

import com.itemmanagement.entity.enums.StoreStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Entity
@Table(name = "stores")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String storeCode;

    @Column(nullable = false, length = 150)
    private String storeName;

    @Column(nullable = false, length = 150)
    private String ownerName;

    @Column(nullable = false, length = 150)
    private String email;

    @Column(nullable = false, length = 20)
    private String mobile;

    @Column(nullable = false, columnDefinition = "text")
    private String address;

    @Column(length = 30)
    private String gstNumber;

    @Column(nullable = false)
    private boolean taxInclusive;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StoreStatus status;

    @Column(name = "subscription_start_date", nullable = false)
    private LocalDate subscriptionStartDate;

    @Column(name = "subscription_end_date", nullable = false)
    private LocalDate subscriptionEndDate;

    @Column(name = "max_branches", nullable = false)
    private int maxBranches;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) status = StoreStatus.ACTIVE;
        if (subscriptionStartDate == null) subscriptionStartDate = LocalDate.now(ZoneOffset.UTC);
        if (subscriptionEndDate == null) subscriptionEndDate = subscriptionStartDate.plusYears(1);
        if (maxBranches < 1) maxBranches = 5;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
