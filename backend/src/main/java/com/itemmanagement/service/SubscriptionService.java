package com.itemmanagement.service;

import com.itemmanagement.dto.subscription.PlatformContactRequest;
import com.itemmanagement.dto.subscription.PlatformContactResponse;
import com.itemmanagement.dto.subscription.SubscriptionHistoryResponse;
import com.itemmanagement.dto.subscription.SubscriptionRequest;
import com.itemmanagement.dto.subscription.SubscriptionSummaryResponse;
import com.itemmanagement.entity.PlatformSettings;
import com.itemmanagement.entity.Store;
import com.itemmanagement.entity.SubscriptionChangeHistory;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.PlatformSettingsRepository;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.repository.SubscriptionChangeHistoryRepository;
import com.itemmanagement.repository.UserRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    public static final String EXPIRED_MESSAGE =
            "Your subscription has expired. Please contact the Platform Administrator to renew your subscription.";
    public static final String MAX_BRANCH_MESSAGE =
            "You have reached your maximum branch limit. Please contact the Platform Administrator.";

    private final StoreRepository storeRepository;
    private final BranchRepository branchRepository;
    private final SubscriptionChangeHistoryRepository historyRepository;
    private final PlatformSettingsRepository platformSettingsRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public boolean isExpired(Long storeId) {
        return storeRepository.findById(storeId)
                .map(store -> isExpired(store, LocalDate.now(ZoneOffset.UTC)))
                .orElse(true);
    }

    @Transactional(readOnly = true)
    public SubscriptionSummaryResponse currentStoreSummary() {
        Long storeId = TenantContext.storeId();
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> ApiException.notFound("Store not found"));
        return summary(store);
    }

    @Transactional(readOnly = true)
    public SubscriptionSummaryResponse platformStoreSummary(Long storeId) {
        return summary(findStore(storeId));
    }

    @Transactional(readOnly = true)
    public List<SubscriptionHistoryResponse> history(Long storeId) {
        findStore(storeId);
        return historyRepository.findByStoreIdOrderByChangedAtDesc(storeId).stream()
                .map(entry -> new SubscriptionHistoryResponse(
                        entry.getId(),
                        entry.getChangedAt(),
                        entry.getChangedBy(),
                        entry.getChangedByUsername(),
                        entry.getPreviousStartDate(),
                        entry.getNewStartDate(),
                        entry.getPreviousEndDate(),
                        entry.getNewEndDate(),
                        entry.getPreviousMaxBranches(),
                        entry.getNewMaxBranches(),
                        entry.getNote()))
                .toList();
    }

    @Transactional
    public SubscriptionSummaryResponse updateForPlatform(Long storeId, SubscriptionRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw ApiException.badRequest("Subscription end date cannot be before the start date");
        }
        // Serialises subscription edits with concurrent branch creation for the same store.
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> ApiException.notFound("Store not found"));

        LocalDate previousStart = store.getSubscriptionStartDate();
        LocalDate previousEnd = store.getSubscriptionEndDate();
        int previousLimit = store.getMaxBranches();

        boolean changed = !Objects.equals(previousStart, request.startDate())
                || !Objects.equals(previousEnd, request.endDate())
                || previousLimit != request.maxBranches();
        if (!changed) {
            return summary(store);
        }

        store.setSubscriptionStartDate(request.startDate());
        store.setSubscriptionEndDate(request.endDate());
        store.setMaxBranches(request.maxBranches());
        storeRepository.save(store);

        AuthUser actor = TenantContext.user();
        historyRepository.save(SubscriptionChangeHistory.builder()
                .storeId(storeId)
                .changedBy(actor.userId())
                .changedByUsername(actor.username())
                .previousStartDate(previousStart)
                .newStartDate(request.startDate())
                .previousEndDate(previousEnd)
                .newEndDate(request.endDate())
                .previousMaxBranches(previousLimit)
                .newMaxBranches(request.maxBranches())
                .note(blankToNull(request.note()))
                .build());

        auditService.log("UPDATE_SUBSCRIPTION", "STORE", storeId,
                "Subscription " + previousStart + ".." + previousEnd + " -> "
                        + request.startDate() + ".." + request.endDate()
                        + ", branch limit " + previousLimit + " -> " + request.maxBranches());

        return summary(store);
    }

    public PlatformContactResponse contact() {
        PlatformSettings settings = platformSettingsRepository.findById(1).orElse(null);
        String email = settings == null ? null : blankToNull(settings.getContactEmail());
        String phone = settings == null ? null : blankToNull(settings.getContactPhone());
        Instant updatedAt = settings == null ? null : settings.getUpdatedAt();

        if (email == null || phone == null) {
            User fallback = primaryPlatformAdmin();
            if (fallback != null) {
                if (email == null) email = blankToNull(fallback.getEmail());
                if (phone == null) phone = blankToNull(fallback.getMobile());
            }
        }
        return new PlatformContactResponse(email == null ? "" : email, phone == null ? "" : phone, updatedAt);
    }

    @Transactional
    public PlatformContactResponse updateContact(PlatformContactRequest request) {
        PlatformSettings settings = platformSettingsRepository.findById(1)
                .orElseGet(() -> PlatformSettings.builder().id(1).build());
        settings.setContactEmail(request.contactEmail().trim());
        settings.setContactPhone(request.contactPhone().trim());
        platformSettingsRepository.save(settings);
        auditService.log("UPDATE", "PLATFORM_SETTINGS", 1L,
                "Platform contact updated: " + settings.getContactEmail() + " / " + settings.getContactPhone());
        return new PlatformContactResponse(settings.getContactEmail(), settings.getContactPhone(),
                settings.getUpdatedAt());
    }

    private SubscriptionSummaryResponse summary(Store store) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long branchCount = branchRepository.countByStoreId(store.getId());
        long remaining = Math.max(0, store.getMaxBranches() - branchCount);
        long daysRemaining = Math.max(0,
                ChronoUnit.DAYS.between(today, store.getSubscriptionEndDate()));
        PlatformContactResponse contact = contact();
        return new SubscriptionSummaryResponse(
                store.getId(),
                store.getStoreCode(),
                store.getStoreName(),
                store.getSubscriptionStartDate(),
                store.getSubscriptionEndDate(),
                daysRemaining,
                store.getMaxBranches(),
                branchCount,
                remaining,
                isExpired(store, today),
                contact.contactEmail(),
                contact.contactPhone());
    }

    private boolean isExpired(Store store, LocalDate today) {
        return store.getSubscriptionEndDate().isBefore(today);
    }

    private User primaryPlatformAdmin() {
        List<User> admins = userRepository.findPlatformAdmins();
        return admins.stream()
                .filter(user -> user.getStatus() == StoreStatus.ACTIVE)
                .filter(user -> "platform".equalsIgnoreCase(user.getUsername()))
                .findFirst()
                .or(() -> admins.stream().filter(user -> user.getStatus() == StoreStatus.ACTIVE).findFirst())
                .orElse(admins.isEmpty() ? null : admins.get(0));
    }

    private Store findStore(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> ApiException.notFound("Store not found"));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
