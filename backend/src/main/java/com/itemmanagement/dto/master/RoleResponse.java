package com.itemmanagement.dto.master;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RoleResponse(
        Long id,
        String code,
        String name,
        String description,
        boolean system,
        List<PermissionResponse> permissions,
        long userCount
) {
    public record Request(
            @NotBlank(message = "Role code is required")
            @Pattern(regexp = "^[A-Za-z0-9_]{2,50}$", message = "Use 2-50 letters, numbers or underscore")
            String code,
            @NotBlank(message = "Role name is required") @Size(max = 100) String name,
            @Size(max = 255) String description,
            List<String> permissionCodes
    ) {
    }

    public record PermissionResponse(Long id, String code, String name, String description) {
    }
}
