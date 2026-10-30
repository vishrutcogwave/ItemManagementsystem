package com.itemmanagement.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterStoreRequest(
        @NotBlank(message = "Store code is required")
        @Pattern(regexp = "^[A-Za-z0-9_-]{3,30}$", message = "Use 3-30 letters, numbers, hyphen or underscore")
        String storeCode,

        @NotBlank(message = "Store name is required") @Size(max = 150) String storeName,
        @NotBlank(message = "Owner name is required") @Size(max = 150) String ownerName,
        @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email,
        @NotBlank(message = "Mobile number is required") @Size(max = 20) String mobile,
        @NotBlank(message = "Address is required") String address,
        @Size(max = 30) String gstNumber,
        @Size(max = 80) String state,

        @NotBlank(message = "Login username is required")
        @Pattern(regexp = "^[A-Za-z0-9._-]{3,40}$", message = "Use 3-40 letters, numbers, dot, underscore or hyphen")
        String username,
        @NotBlank(message = "Password is required") @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
        String password,

        @Size(max = 150) String branchName
) {
}
