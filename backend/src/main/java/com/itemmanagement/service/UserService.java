package com.itemmanagement.service;

import com.itemmanagement.dto.master.RoleResponse;
import com.itemmanagement.dto.master.UserResponse;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.Permission;
import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.PermissionRepository;
import com.itemmanagement.repository.RoleRepository;
import com.itemmanagement.repository.UserRepository;
import com.itemmanagement.security.PasswordPolicy;
import com.itemmanagement.security.PlatformPolicy;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PageResponse;
import com.itemmanagement.util.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BranchRepository branchRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(String search, StoreStatus status, int page, int size) {
        Long storeId = TenantContext.storeId();
        Page<User> users = userRepository.search(storeId, clean(search), status,
                Pagination.of(page, size));
        return PageResponse.of(users, this::toResponse);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        return toResponse(find(id, TenantContext.storeId()));
    }

    @Transactional
    public UserResponse create(UserResponse.CreateRequest request) {
        Long storeId = TenantContext.storeId();
        String username = request.username().trim();
        if (userRepository.existsByStoreIdAndUsernameIgnoreCase(storeId, username)) {
            throw ApiException.conflict("Username is already taken for this store");
        }
        passwordPolicy.validate(request.password(), username, request.email());
        Role role = role(storeId, request.roleId());
        Set<Branch> branches = branches(storeId, request.branchIds(), role);
        User user = userRepository.save(User.builder()
                .storeId(storeId)
                .username(username)
                .email(request.email().trim())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .mobile(blankToNull(request.mobile()))
                .status(StoreStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(role)))
                .branches(branches)
                .build());
        auditService.logRequired("CREATE", "USER", user.getId(), "Created user " + user.getUsername());
        return toResponse(user);
    }

    @Transactional
    public UserResponse update(Long id, UserResponse.UpdateRequest request) {
        Long storeId = TenantContext.storeId();
        User user = find(id, storeId);
        Role role = role(storeId, request.roleId());
        Set<Branch> branches = branches(storeId, request.branchIds(), role);
        // Branch scope is taken from the token, so a change to roles or branches
        // must invalidate existing tokens instead of waiting for them to expire.
        boolean authorizationChanged = !roleIdSet(user.getRoles()).equals(Set.of(role.getId()))
                || !branchIdSet(user.getBranches()).equals(branchIdSet(branches));
        if (request.status() == StoreStatus.INACTIVE && user.getId().equals(TenantContext.userId())) {
            throw ApiException.badRequest("You cannot deactivate your own account");
        }
        user.setFullName(request.fullName().trim());
        user.setEmail(request.email().trim());
        user.setMobile(blankToNull(request.mobile()));
        user.getRoles().clear();
        user.getRoles().add(role);
        user.getBranches().clear();
        user.getBranches().addAll(branches);
        if (request.status() != null) {
            user.setStatus(request.status());
        }
        boolean passwordChangedByAdmin = request.password() != null && !request.password().isBlank();
        if (passwordChangedByAdmin) {
            passwordPolicy.validate(request.password(), user.getUsername(), request.email());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            user.setMustChangePassword(true);
            user.setTokenVersion(user.getTokenVersion() + 1);
        } else if (authorizationChanged) {
            user.setTokenVersion(user.getTokenVersion() + 1);
        }
        userRepository.save(user);
        auditService.logRequired("UPDATE", "USER", userIdSafe(user),
                "Updated user " + user.getUsername()
                        + (passwordChangedByAdmin ? "; password reset by an administrator and change forced" : "")
                        + (authorizationChanged && !passwordChangedByAdmin
                                ? "; access scope changed and existing sessions were invalidated" : ""));
        return toResponse(user);
    }

    @Transactional
    public UserResponse assignBranches(Long id, List<Long> branchIds) {
        Long storeId = TenantContext.storeId();
        User user = find(id, storeId);
        Set<Branch> branches = branches(storeId, branchIds, null);
        boolean branchesChanged = !branchIdSet(user.getBranches()).equals(branchIdSet(branches));
        user.getBranches().clear();
        user.getBranches().addAll(branches);
        if (branchesChanged) {
            user.setTokenVersion(user.getTokenVersion() + 1);
        }
        userRepository.save(user);
        auditService.logRequired("ASSIGN_BRANCHES", "USER", userIdSafe(user),
                "Assigned " + branches.size() + " branch(es) to " + user.getUsername()
                        + (branchesChanged ? "; existing sessions were invalidated" : ""));
        return toResponse(user);
    }

    @Transactional
    public UserResponse updateStatus(Long id, StoreStatus status) {
        Long storeId = TenantContext.storeId();
        User user = find(id, storeId);
        if (status == StoreStatus.INACTIVE && user.getId().equals(TenantContext.userId())) {
            throw ApiException.badRequest("You cannot deactivate your own account");
        }
        user.setStatus(status);
        userRepository.save(user);
        auditService.logRequired(status == StoreStatus.ACTIVE ? "ACTIVATE" : "DEACTIVATE", "USER",
                userIdSafe(user), "User status changed to " + status);
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> roles() {
        Long storeId = TenantContext.storeId();
        return roleRepository.findByStoreIdOrderByName(storeId).stream()
                .filter(role -> !PlatformPolicy.isReservedRoleCode(role.getCode()))
                .map(this::roleResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoleResponse.PermissionResponse> permissions() {
        boolean storeScoped = TenantContext.storeId() != null;
        return permissionRepository.findAllByOrderByCode().stream()
                .filter(permission -> !storeScoped || !PlatformPolicy.isPlatformOnlyPermission(permission.getCode()))
                .map(this::permissionResponse)
                .toList();
    }

    @Transactional
    public RoleResponse createRole(RoleResponse.Request request) {
        Long storeId = TenantContext.storeId();
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (PlatformPolicy.isReservedRoleCode(code)) {
            throw ApiException.forbidden(
                    "Role codes starting with PLATFORM_ are reserved for platform administrators");
        }
        if (roleRepository.existsByStoreIdAndCode(storeId, code)) {
            throw ApiException.conflict("A role with this code already exists");
        }
        Set<Permission> permissions = storePermissions(request.permissionCodes());
        Role role = roleRepository.save(Role.builder()
                .storeId(storeId)
                .code(code)
                .name(request.name().trim())
                .description(blankToNull(request.description()))
                .isSystem(false)
                .permissions(permissions)
                .build());
        auditService.logRequired("CREATE", "ROLE", role.getId(), "Created role " + role.getName());
        return roleResponse(role);
    }

    @Transactional
    public RoleResponse updateRole(Long id, RoleResponse.Request request) {
        Long storeId = TenantContext.storeId();
        Role role = roleRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Role not found"));
        if (PlatformPolicy.isReservedRoleCode(role.getCode())) {
            throw ApiException.forbidden("Platform roles can only be managed by platform administrators");
        }
        if (PlatformPolicy.isReservedRoleCode(request.code())) {
            throw ApiException.forbidden(
                    "Role codes starting with PLATFORM_ are reserved for platform administrators");
        }
        role.setName(request.name().trim());
        role.setDescription(blankToNull(request.description()));
        role.getPermissions().clear();
        role.getPermissions().addAll(storePermissions(request.permissionCodes()));
        roleRepository.save(role);
        auditService.logRequired("UPDATE", "ROLE", role.getId(), "Updated role permissions for " + role.getName());
        return roleResponse(role);
    }

    private Set<Permission> storePermissions(List<String> permissionCodes) {
        List<String> codes = permissionCodes == null ? List.of() : permissionCodes;
        String platformOnly = codes.stream()
                .filter(PlatformPolicy::isPlatformOnlyPermission)
                .findFirst()
                .orElse(null);
        if (platformOnly != null) {
            throw ApiException.forbidden(
                    "Permission " + platformOnly.toUpperCase(Locale.ROOT)
                            + " is reserved for platform administrators");
        }
        return new HashSet<>(permissionRepository.findByCodeIn(codes));
    }

    private Set<Branch> branches(Long storeId, List<Long> branchIds, Role role) {
        List<Long> ids = branchIds == null ? List.of() : branchIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            if (role != null && (StoreProvisioningService.BRANCH_MANAGER.equals(role.getCode())
                    || StoreProvisioningService.BRANCH_STAFF.equals(role.getCode()))) {
                throw ApiException.badRequest("Assign at least one branch to branch-level users");
            }
            return new HashSet<>();
        }
        List<Branch> found = branchRepository.findByStoreIdAndIdIn(storeId, ids);
        if (found.size() != ids.size()) {
            throw ApiException.badRequest("One or more branches do not belong to this store");
        }
        return new HashSet<>(found);
    }

    private Role role(Long storeId, Long roleId) {
        Role role = roleRepository.findByIdAndStoreId(roleId, storeId)
                .orElseThrow(() -> ApiException.badRequest("Select a valid role for this store"));
        if (PlatformPolicy.isReservedRoleCode(role.getCode())) {
            throw ApiException.forbidden("Platform roles cannot be assigned to store users");
        }
        return role;
    }

    private User find(Long id, Long storeId) {
        return userRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
    }

    private Set<Long> roleIdSet(Set<Role> roles) {
        return roles.stream().map(Role::getId).collect(java.util.stream.Collectors.toSet());
    }

    private Set<Long> branchIdSet(Set<Branch> branches) {
        return branches.stream().map(Branch::getId).collect(java.util.stream.Collectors.toSet());
    }

    private UserResponse toResponse(User user) {
        List<UserResponse.RoleSummary> roles = user.getRoles().stream()
                .sorted(Comparator.comparing(Role::getName))
                .map(r -> new UserResponse.RoleSummary(r.getId(), r.getCode(), r.getName()))
                .toList();
        List<UserResponse.BranchSummary> branches = user.getBranches().stream()
                .sorted(Comparator.comparing(Branch::getBranchName))
                .map(b -> new UserResponse.BranchSummary(b.getId(), b.getBranchCode(), b.getBranchName()))
                .toList();
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(), user.getEmail(),
                user.getMobile(), user.getStatus().name(), roles, branches, user.getCreatedAt());
    }

    private RoleResponse roleResponse(Role role) {
        List<RoleResponse.PermissionResponse> permissions = role.getPermissions().stream()
                .filter(permission -> role.getStoreId() == null
                        || !PlatformPolicy.isPlatformOnlyPermission(permission.getCode()))
                .sorted(Comparator.comparing(Permission::getCode))
                .map(this::permissionResponse)
                .toList();
        long userCount = role.getStoreId() == null ? 0
                : userRepository.countByStoreIdAndRoleCode(role.getStoreId(), role.getCode());
        return new RoleResponse(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                role.isSystem(), permissions, userCount);
    }

    private RoleResponse.PermissionResponse permissionResponse(Permission permission) {
        return new RoleResponse.PermissionResponse(permission.getId(), permission.getCode(),
                permission.getName(), permission.getDescription());
    }

    private Long userIdSafe(User user) {
        return user.getId();
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
