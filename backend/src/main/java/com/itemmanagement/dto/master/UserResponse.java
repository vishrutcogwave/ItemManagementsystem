package com.itemmanagement.dto.master;

import com.itemmanagement.entity.enums.StoreStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record UserResponse(
        Long id,
        String username,
        String fullName,
        String email,
        String mobile,
        String status,
        List<RoleSummary> roles,
        List<BranchSummary> branches,
        Instant createdAt
) {
    public record RoleSummary(Long id, String code, String name) {
    }

    public record BranchSummary(Long id, String branchCode, String branchName) {
    }

    public record CreateRequest(
            @NotBlank(message = "Username is required")
            @Pattern(regexp = "^[A-Za-z0-9._-]{3,40}$", message = "Use 3-40 letters, numbers, dot, underscore or hyphen")
            String username,
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email,
            @NotBlank(message = "Password is required") @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            String password,
            @NotBlank(message = "Full name is required") @Size(max = 150) String fullName,
            @Size(max = 20) String mobile,
            @NotNull(message = "Role is required") Long roleId,
            List<Long> branchIds
    ) {
    }

    public record UpdateRequest(
            @NotBlank(message = "Full name is required") @Size(max = 150) String fullName,
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email,
            @Size(max = 20) String mobile,
            @NotNull(message = "Role is required") Long roleId,
            List<Long> branchIds,
            StoreStatus status,
            @Size(max = 72) String password
    ) {
    }

    public record AssignBranchesRequest(List<Long> branchIds) {
    }
}
