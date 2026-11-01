package com.itemmanagement.dto.platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PlatformAccountRequests {

    private PlatformAccountRequests() {
    }

    public record UsernameUpdateRequest(
            @NotBlank(message = "Username is required")
            @Size(min = 3, max = 80, message = "Username must be 3-80 characters")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                    message = "Use letters, numbers, dot, underscore or hyphen only")
            String username
    ) {
    }

    public record ResetPasswordRequest(
            @NotNull(message = "Password reset confirmation is required")
            Boolean confirm
    ) {
    }
}
