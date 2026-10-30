package com.itemmanagement.dto.master;

import com.itemmanagement.entity.enums.StoreStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record BranchResponse(
        Long id,
        Long storeId,
        String branchCode,
        String branchName,
        String address,
        String contactNumber,
        String state,
        String status,
        Instant createdAt
) {
    public record Request(
            @NotBlank(message = "Branch code is required")
            @Pattern(regexp = "^[A-Za-z0-9_-]{2,30}$", message = "Use 2-30 letters, numbers, hyphen or underscore")
            String branchCode,
            @NotBlank(message = "Branch name is required") @Size(max = 150) String branchName,
            @NotBlank(message = "Address is required") String address,
            @NotBlank(message = "Contact number is required") @Size(max = 20) String contactNumber,
            @Size(max = 80) String state,
            StoreStatus status
    ) {
    }
}
