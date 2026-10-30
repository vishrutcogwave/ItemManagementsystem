package com.itemmanagement.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Store code is required") String storeCode,
        @NotBlank(message = "Username or email is required") String username,
        @NotBlank(message = "Password is required") String password
) {
}
