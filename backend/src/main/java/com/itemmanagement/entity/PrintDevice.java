package com.itemmanagement.entity;

import com.itemmanagement.entity.enums.PrintDevicePlatform;
import com.itemmanagement.entity.enums.PrintDeviceStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "print_devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrintDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long branchId;

    @Column(nullable = false, length = 100)
    private String deviceName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PrintDevicePlatform platform;

    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PrintDeviceStatus status;

    private Long pairedBy;

    @Column(length = 80)
    private String pairedByUsername;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant lastSeenAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = PrintDeviceStatus.ACTIVE;
    }
}
