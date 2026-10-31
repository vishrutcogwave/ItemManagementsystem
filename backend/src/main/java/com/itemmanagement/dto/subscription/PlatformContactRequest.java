package com.itemmanagement.dto.subscription;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlatformContactRequest(
        @NotBlank(message = "Contact email is required")
        @Email(message = "Enter a valid email")
        @Size(max = 150)
        String contactEmail,

        @NotBlank(message = "Contact phone is required")
        @Size(max = 20)
        String contactPhone
) {
}
