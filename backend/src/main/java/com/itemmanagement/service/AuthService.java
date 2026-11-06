package com.itemmanagement.service;

import com.itemmanagement.dto.auth.AuthResponse;
import com.itemmanagement.dto.auth.ChangePasswordRequest;
import com.itemmanagement.dto.auth.LoginRequest;
import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.dto.common.MessageResponse;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.Store;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.repository.UserRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.JwtService;
import com.itemmanagement.security.LoginAttemptService;
import com.itemmanagement.security.PasswordPolicy;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.security.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String PLATFORM_CODE = "PLATFORM";

    private final StoreRepository storeRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final StoreProvisioningService provisioningService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final JwtService jwtService;
    private final TokenBlacklistService blacklistService;
    private final LoginAttemptService loginAttemptService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request, String clientAddress) {
        String storeCode = request.storeCode().trim();
        String attemptKey = loginAttemptService.key(storeCode, request.username(), clientAddress);
        loginAttemptService.checkAllowed(attemptKey);

        User user;
        Store store = null;

        try {
            if (PLATFORM_CODE.equalsIgnoreCase(storeCode)) {
                user = userRepository.findByUsernameIgnoreCaseAndStoreIdIsNull(request.username().trim())
                        .or(() -> userRepository.findByEmailIgnoreCaseAndStoreIdIsNull(request.username().trim()))
                        .orElseThrow(() -> ApiException.unauthorized("Invalid store code, username or password"));
            } else {
                store = storeRepository.findByStoreCodeIgnoreCase(storeCode)
                        .orElseThrow(() -> ApiException.unauthorized("Invalid store code, username or password"));
                user = findStoreUser(store.getId(), request.username());
            }
        } catch (ApiException ex) {
            if (ex.getStatus() == HttpStatus.UNAUTHORIZED) {
                // Blacklist any active token that was used to make this failed attempt
                blacklistTokensAfterFailedLogin(storeCode, request.username(), clientAddress);
                loginAttemptService.recordFailure(attemptKey);
            }
            throw ex;
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptService.recordFailure(attemptKey);
            throw ApiException.unauthorized("Invalid store code, username or password");
        }
        if (user.getStatus() != StoreStatus.ACTIVE) {
            throw ApiException.forbidden("This user account is inactive");
        }
        if (store == null && user.getRoles().stream().noneMatch(r -> StoreProvisioningService.PLATFORM_ADMIN.equals(r.getCode()))) {
            loginAttemptService.recordFailure(attemptKey);
            throw ApiException.unauthorized("Invalid store code, username or password");
        }
        if (store != null) {
            store = storeRepository.findById(store.getId())
                    .orElseThrow(() -> ApiException.unauthorized("Store not found"));
            if (store.getStatus() != StoreStatus.ACTIVE) {
                throw ApiException.forbidden("This store account is inactive. Contact the platform administrator.");
            }
        }
        loginAttemptService.recordSuccess(attemptKey);
        AuthResponse response = buildSession(user, store, true);
        auditService.logFor(store == null ? null : store.getId(), user.getId(), user.getUsername(),
                "LOGIN", "USER", user.getId(), "Successful login");
        return response;
    }

    @Transactional(readOnly = true)
    public AuthResponse me() {
        AuthUser current = TenantContext.user();
        User user = userRepository.findById(current.userId())
                .orElseThrow(() -> ApiException.unauthorized("User no longer exists"));
        Store store = user.getStoreId() == null ? null
                : storeRepository.findById(user.getStoreId())
                .orElseThrow(() -> ApiException.unauthorized("Store no longer exists"));
        return buildSession(user, store, false);
    }

    @Transactional(readOnly = true)
    public AuthResponse refresh(String currentToken) {
        AuthUser current = TenantContext.user();
        User user = userRepository.findById(current.userId())
                .orElseThrow(() -> ApiException.unauthorized("User no longer exists"));
        Store store = user.getStoreId() == null ? null
                : storeRepository.findById(user.getStoreId())
                .orElseThrow(() -> ApiException.unauthorized("Store no longer exists"));
        blacklistToken(currentToken);
        return buildSession(user, store, true);
    }

    public void logout(String currentToken) {
        blacklistToken(currentToken);
    }

    /**
     * Self-service password change for both platform administrators and store
     * users. The current password is required, the new password must satisfy the
     * shared policy, and every previously issued token for the user is invalidated
     * by bumping the user's token version (other sessions are also blacklisted).
     */
    @Transactional
    public AuthResponse changePassword(ChangePasswordRequest request, String currentToken) {
        AuthUser current = TenantContext.user();
        if (current == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        User user = userRepository.findById(current.userId())
                .orElseThrow(() -> ApiException.unauthorized("User no longer exists"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Current password is incorrect",
                    Map.of("currentPassword", "Current password is incorrect"));
        }
        passwordPolicy.validate(request.newPassword(), user.getUsername(), user.getEmail());
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("New password must be different from the current password");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        blacklistToken(currentToken);
        Store store = user.getStoreId() == null ? null
                : storeRepository.findById(user.getStoreId()).orElse(null);
        auditService.logFor(user.getStoreId(), user.getId(), user.getUsername(),
                "PASSWORD_CHANGE", "USER", user.getId(),
                "User changed their own password; all other sessions were invalidated");
        return buildSession(user, store, true);
    }

    @Transactional
    public MessageResponse registerStore(RegisterStoreRequest request) {
        StoreProvisioningService.ProvisionedStore provisioned = provisioningService.createStore(request, false);
        auditService.logFor(provisioned.store().getId(), provisioned.owner().getId(),
                provisioned.owner().getUsername(), "REGISTER_STORE", "STORE",
                provisioned.store().getId(), "Store registered: " + provisioned.store().getStoreName());
        return new MessageResponse("Store registered successfully. You can now sign in with store code "
                + provisioned.store().getStoreCode() + ".");
    }

    private User findStoreUser(Long storeId, String login) {
        return userRepository.findByStoreIdAndUsernameIgnoreCase(storeId, login.trim())
                .or(() -> userRepository.findByStoreIdAndEmailIgnoreCase(storeId, login.trim()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid store code, username or password"));
    }

    private AuthResponse buildSession(User user, Store store, boolean issueToken) {
        Set<String> roles = user.getRoles().stream()
                .map(Role::getCode)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (roles.isEmpty()) {
            throw ApiException.forbidden("No role is assigned to this account");
        }
        Set<String> permissions = user.getRoles().stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(permission -> permission.getCode())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        boolean platformAdmin = store == null;
        boolean allBranches = !platformAdmin && (roles.contains(StoreProvisioningService.STORE_OWNER)
                || roles.contains(StoreProvisioningService.STORE_ADMIN));

        List<Branch> accessibleBranches = List.of();
        if (store != null) {
            List<Branch> activeBranches = branchRepository
                    .findByStoreIdAndStatusOrderByBranchName(store.getId(), StoreStatus.ACTIVE);
            if (allBranches) {
                accessibleBranches = activeBranches;
            } else {
                Set<Long> assigned = user.getBranches().stream().map(Branch::getId).collect(Collectors.toSet());
                accessibleBranches = activeBranches.stream().filter(b -> assigned.contains(b.getId())).toList();
            }
        }

        Set<Long> branchIds = accessibleBranches.stream().map(Branch::getId)
                .collect(Collectors.toCollection(HashSet::new));

        AuthUser principal = new AuthUser(
                user.getId(),
                store == null ? null : store.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getEmail(),
                user.getMobile(),
                store == null ? null : store.getStoreCode(),
                store == null ? null : store.getStoreName(),
                roles,
                permissions,
                branchIds,
                allBranches
        );

        String token = issueToken ? jwtService.generate(principal, user.getTokenVersion()) : null;
        List<AuthResponse.BranchProfile> branchProfiles = accessibleBranches.stream()
                .sorted(Comparator.comparing(Branch::getBranchName))
                .map(b -> new AuthResponse.BranchProfile(b.getId(), b.getBranchCode(), b.getBranchName(),
                        b.getState()))
                .toList();

        return new AuthResponse(
                token,
                token == null ? null : "Bearer",
                jwtService.getExpirationSeconds(),
                new AuthResponse.UserProfile(user.getId(), user.getUsername(), user.getFullName(),
                        user.getEmail(), user.getMobile()),
                store == null ? null : new AuthResponse.StoreProfile(store.getId(), store.getStoreCode(),
                        store.getStoreName(), store.getOwnerName(), store.getEmail(), store.getMobile(),
                        store.getAddress(), store.getGstNumber(), store.isTaxInclusive(),
                        store.getStatus().name()),
                branchProfiles,
                roles,
                permissions,
                allBranches,
                platformAdmin,
                user.isMustChangePassword()
        );
    }

    private void blacklistToken(String token) {
        if (token == null || token.isBlank()) return;
        try {
            Claims claims = jwtService.parse(token.trim());
            blacklistService.revoke(claims.getId(), jwtService.expiry(claims));
        } catch (Exception ignored) {
            // Invalid or already-expired tokens need no blacklist entry.
        }
    }

    private void blacklistTokensAfterFailedLogin(String storeCode, String username, String clientAddress) {
        try {
            // Find a token that matches the failed credentials to blacklist it
            // The key is: store_code|username|client_address
            String key = storeCode + "|" + username + "|" + clientAddress;
            
            // Parse any active JWT we can find for this user
            // We'll check both platform admin login (username only) and store user login (store+user)
            blacklistService.revokeActiveTokens(storeCode, username, clientAddress);
        } catch (Exception e) {
            log.debug(\"Failed to blacklist tokens after failed login: {}\", e.getMessage());
        }
    }
}
