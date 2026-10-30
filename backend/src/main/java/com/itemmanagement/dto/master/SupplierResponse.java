package com.itemmanagement.dto.master;

import com.itemmanagement.entity.enums.StoreStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record SupplierResponse(
        Long id,
        String supplierName,
        String mobile,
        String address,
        String state,
        String gstNumber,
        String email,
        String status,
        Instant createdAt
) {
    public record Request(
            @NotBlank(message = "Supplier name is required") @Size(max = 150) String supplierName,
            @NotBlank(message = "Mobile number is required") @Size(max = 20) String mobile,
            @NotBlank(message = "Address is required") String address,
            @Size(max = 80) String state,
            @Size(max = 30) String gstNumber,
            @Email(message = "Enter a valid email") @Size(max = 150) String email,
            StoreStatus status
    ) {
    }
}
