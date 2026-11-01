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
        auditService.log("CREATE", "USER", user.getId(), "Created user " + user.getUsername());
        return toResponse(user);
    }

    @Transactional
    public UserResponse update(Long id, UserResponse.UpdateRequest request) {
        Long storeId = TenantContext.storeId();
        User user = find(id, storeId);
        Role role = role(storeId, request.roleId());
        Set<Branch> branches = branches(storeId, request.branchIds(), role);
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
        }
        userRepository.save(user);
        auditService.log("UPDATE", "USER", userIdSafe(user),
                "Updated user " + user.getUsername()
                        + (passwordChangedByAdmin ? "; password reset by an administrator and change forced" : ""));
        return toResponse(user);
    }

    @Transactional
    public UserResponse assignBranches(Long id, List<Long> branchIds) {
        Long storeId = TenantContext.storeId();
        User user = find(id, storeId);
        Set<Branch> branches = branches(storeId, branchIds, null);
        user.getBranches().clear();
        user.getBranches().addAll(branches);
        userRepository.save(user);
        auditService.log("ASSIGN_BRANCHES", "USER", userIdSafe(user),
                "Assigned " + branches.size() + " branch(es) to " + user.getUsername());
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
        auditService.log(status == StoreStatus.ACTIVE ? "ACTIVATE" : "DEACTIVATE", "USER",
                userIdSafe(user), "User status changed to " + status);
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> roles() {
        Long storeId = TenantContext.storeId();
        return roleRepository.findByStoreIdOrderByName(storeId).stream()
                .map(this::roleResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoleResponse.PermissionResponse> permissions() {
        return permissionRepository.findAllByOrderByCode().stream()
                .map(this::permissionResponse)
                .toList();
    }

    @Transactional
    public RoleResponse createRole(RoleResponse.Request request) {
        Long storeId = TenantContext.storeId();
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (roleRepository.existsByStoreIdAndCode(storeId, code)) {
            throw ApiException.conflict("A role with this code already exists");
        }
        Set<Permission> permissions = new HashSet<>(permissionRepository.findByCodeIn(
                request.permissionCodes() == null ? List.of() : request.permissionCodes()));
        Role role = roleRepository.save(Role.builder()
                .storeId(storeId)
                .code(code)
                .name(request.name().trim())
                .description(blankToNull(request.description()))
                .isSystem(false)
                .permissions(permissions)
                .build());
        auditService.log("CREATE", "ROLE", role.getId(), "Created role " + role.getName());
        return roleResponse(role);
    }

    @Transactional
    public RoleResponse updateRole(Long id, RoleResponse.Request request) {
        Long storeId = TenantContext.storeId();
        Role role = roleRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Role not found"));
        role.setName(request.name().trim());
        role.setDescription(blankToNull(request.description()));
        role.getPermissions().clear();
        role.getPermissions().addAll(permissionRepository.findByCodeIn(
                request.permissionCodes() == null ? List.of() : request.permissionCodes()));
        roleRepository.save(role);
        auditService.log("UPDATE", "ROLE", role.getId(), "Updated role permissions for " + role.getName());
        return roleResponse(role);
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
        return roleRepository.findByIdAndStoreId(roleId, storeId)
                .orElseThrow(() -> ApiException.badRequest("Select a valid role for this store"));
    }

    private User find(Long id, Long storeId) {
        return userRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
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
