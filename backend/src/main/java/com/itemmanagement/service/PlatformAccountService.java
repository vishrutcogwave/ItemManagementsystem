package com.itemmanagement.service;

import com.itemmanagement.dto.platform.CredentialHistoryResponse;
import com.itemmanagement.dto.platform.PlatformAccountRequests.ResetPasswordRequest;
import com.itemmanagement.dto.platform.PlatformAccountRequests.UsernameUpdateRequest;
import com.itemmanagement.dto.platform.PlatformAccountResponse;
import com.itemmanagement.dto.platform.PlatformPasswordResetResponse;
import com.itemmanagement.entity.AuditLog;
import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.Store;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.AuditLogRepository;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.repository.UserRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PageResponse;
import com.itemmanagement.util.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Platform-administrator management of store login accounts. Only platform
 * accounts (users without a store) may use this service, and only store-level
 * users are exposed. Password hashes are never returned: a reset generates a
 * one-time temporary password that is shown once and immediately hashed.
 */
@Service
@RequiredArgsConstructor
public class PlatformAccountService {

    private static final int TEMP_PASSWORD_LENGTH = 14;
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";
    private static final String SPECIAL = "!@#$%&*";
    private static final String ALL = LOWER + UPPER + DIGITS + SPECIAL;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> CREDENTIAL_ACTIONS = Set.of(
            "PASSWORD_RESET", "PASSWORD_CHANGE", "USERNAME_UPDATE", "ACTIVATE", "DEACTIVATE");

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<PlatformAccountResponse> list(String search, StoreStatus status,
                                                      Long storeId, int page, int size) {
        requirePlatformAdmin();
        Page<User> accounts = userRepository.searchStoreAccounts(storeId, clean(search), status,
                Pagination.of(page, size));
        Map<Long, Store> stores = storesFor(accounts.getContent());
        return PageResponse.of(accounts, user -> toResponse(user, stores.get(user.getStoreId())));
    }

    @Transactional(readOnly = true)
    public PlatformAccountResponse get(Long id) {
        requirePlatformAdmin();
        User user = findStoreAccount(id);
        return toResponse(user, storeRepository.findById(user.getStoreId()).orElse(null));
    }

    @Transactional
    public PlatformAccountResponse updateUsername(Long id, UsernameUpdateRequest request) {
        AuthUser actor = requirePlatformAdmin();
        User user = findStoreAccount(id);
        String username = request.username().trim();
        if (userRepository.existsByStoreIdAndUsernameIgnoreCaseAndIdNot(
                user.getStoreId(), username, user.getId())) {
            throw ApiException.conflict("Username is already taken for this store");
        }
        String previous = user.getUsername();
        if (previous.equalsIgnoreCase(username)) {
            return toResponse(user, storeRepository.findById(user.getStoreId()).orElse(null));
        }
        user.setUsername(username);
        userRepository.save(user);
        auditService.logFor(user.getStoreId(), actor.userId(), actor.username(),
                "USERNAME_UPDATE", "USER", user.getId(),
                "Username changed from '" + previous + "' to '" + username + "'");
        return toResponse(user, storeRepository.findById(user.getStoreId()).orElse(null));
    }

    @Transactional
    public PlatformAccountResponse updateStatus(Long id, StoreStatus status) {
        if (status == null) {
            throw ApiException.badRequest("Select a valid account status");
        }
        AuthUser actor = requirePlatformAdmin();
        User user = findStoreAccount(id);
        if (status == StoreStatus.ACTIVE && user.getStatus() == StoreStatus.ACTIVE) {
            return toResponse(user, storeRepository.findById(user.getStoreId()).orElse(null));
        }
        if (status == StoreStatus.INACTIVE && user.getStatus() == StoreStatus.INACTIVE) {
            return toResponse(user, storeRepository.findById(user.getStoreId()).orElse(null));
        }
        user.setStatus(status);
        userRepository.save(user);
        auditService.logFor(user.getStoreId(), actor.userId(), actor.username(),
                status == StoreStatus.ACTIVE ? "ACTIVATE" : "DEACTIVATE", "USER", user.getId(),
                "Store login account " + user.getUsername() + " set to " + status);
        return toResponse(user, storeRepository.findById(user.getStoreId()).orElse(null));
    }

    @Transactional
    public PlatformPasswordResetResponse resetPassword(Long id, ResetPasswordRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            throw ApiException.badRequest("Confirm the password reset before continuing");
        }
        AuthUser actor = requirePlatformAdmin();
        User user = findStoreAccount(id);
        String temporaryPassword = generateTemporaryPassword();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        Store store = storeRepository.findById(user.getStoreId()).orElse(null);
        auditService.logFor(user.getStoreId(), actor.userId(), actor.username(),
                "PASSWORD_RESET", "USER", user.getId(),
                "Temporary password generated for " + user.getUsername()
                        + "; a password change is forced at the next sign-in and existing sessions were invalidated");
        return new PlatformPasswordResetResponse(user.getId(), user.getUsername(),
                store == null ? null : store.getStoreCode(), temporaryPassword, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<CredentialHistoryResponse> history(Long id) {
        requirePlatformAdmin();
        User user = findStoreAccount(id);
        return auditLogRepository
                .findByEntityTypeAndEntityIdOrderByCreatedAtDescIdDesc("USER", user.getId()).stream()
                .filter(log -> CREDENTIAL_ACTIONS.contains(log.getAction()))
                .map(log -> new CredentialHistoryResponse(log.getId(), log.getAction(),
                        log.getUsername(), log.getDetails(), log.getCreatedAt()))
                .toList();
    }

    private AuthUser requirePlatformAdmin() {
        AuthUser actor = TenantContext.user();
        if (actor == null || !actor.isPlatformAdmin()) {
            throw ApiException.forbidden("Only platform administrators can manage store accounts");
        }
        return actor;
    }

    private User findStoreAccount(Long id) {
        return userRepository.findById(id)
                .filter(user -> user.getStoreId() != null)
                .orElseThrow(() -> ApiException.notFound("Store account not found"));
    }

    private Map<Long, Store> storesFor(List<User> users) {
        Set<Long> ids = users.stream().map(User::getStoreId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return storeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Store::getId, Function.identity()));
    }

    private PlatformAccountResponse toResponse(User user, Store store) {
        List<PlatformAccountResponse.RoleSummary> roles = user.getRoles().stream()
                .sorted(Comparator.comparing(Role::getName))
                .map(role -> new PlatformAccountResponse.RoleSummary(
                        role.getId(), role.getCode(), role.getName()))
                .toList();
        boolean owner = roles.stream()
                .anyMatch(role -> StoreProvisioningService.STORE_OWNER.equals(role.code()));
        return new PlatformAccountResponse(user.getId(), user.getStoreId(),
                store == null ? null : store.getStoreCode(),
                store == null ? null : store.getStoreName(),
                user.getUsername(), user.getFullName(), user.getEmail(), user.getMobile(),
                user.getStatus().name(), roles, owner, user.getCreatedAt(), user.getUpdatedAt());
    }

    private String generateTemporaryPassword() {
        List<Character> characters = new ArrayList<>(TEMP_PASSWORD_LENGTH);
        characters.add(LOWER.charAt(RANDOM.nextInt(LOWER.length())));
        characters.add(UPPER.charAt(RANDOM.nextInt(UPPER.length())));
        characters.add(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        characters.add(SPECIAL.charAt(RANDOM.nextInt(SPECIAL.length())));
        while (characters.size() < TEMP_PASSWORD_LENGTH) {
            characters.add(ALL.charAt(RANDOM.nextInt(ALL.length())));
        }
        for (int index = characters.size() - 1; index > 0; index--) {
            int swap = RANDOM.nextInt(index + 1);
            Character held = characters.get(index);
            characters.set(index, characters.get(swap));
            characters.set(swap, held);
        }
        StringBuilder password = new StringBuilder(TEMP_PASSWORD_LENGTH);
        characters.forEach(password::append);
        return password.toString();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
