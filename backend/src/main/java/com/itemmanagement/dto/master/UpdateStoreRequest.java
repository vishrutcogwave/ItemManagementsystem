package com.itemmanagement.dto.master;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateStoreRequest(
        @NotBlank(message = "Store name is required") @Size(max = 150) String storeName,
        @NotBlank(message = "Owner name is required") @Size(max = 150) String ownerName,
        @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email,
        @NotBlank(message = "Mobile number is required") @Size(max = 20) String mobile,
        @NotBlank(message = "Address is required") String address,
        @Size(max = 30) String gstNumber,
        Boolean taxInclusive
) {
}
