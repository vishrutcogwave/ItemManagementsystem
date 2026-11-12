package com.itemmanagement.entity;

import com.itemmanagement.entity.enums.StoreStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "unit_master")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnitMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false, length = 20)
    private String unitCode;

    @Column(nullable = false, length = 80)
    private String unitName;

    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StoreStatus status;

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
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
