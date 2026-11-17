package com.itemmanagement.controller;

import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.dto.common.StatusRequest;
import com.itemmanagement.dto.master.StoreResponse;
import com.itemmanagement.dto.master.UpdateStoreRequest;
import com.itemmanagement.dto.subscription.SubscriptionHistoryResponse;
import com.itemmanagement.dto.subscription.SubscriptionRequest;
import com.itemmanagement.dto.subscription.SubscriptionSummaryResponse;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.security.PlatformPolicy;
import com.itemmanagement.service.StoreDeletionService;
import com.itemmanagement.service.StoreService;
import com.itemmanagement.service.SubscriptionService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;
    private final StoreDeletionService storeDeletionService;
    private final SubscriptionService subscriptionService;

    @GetMapping
    @PreAuthorize(PlatformPolicy.PLATFORM_ADMIN_ONLY)
    public PageResponse<StoreResponse> list(@RequestParam(required = false) String search,
                                            @RequestParam(required = false) StoreStatus status,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        return storeService.list(search, status, page, size);
    }

    @PostMapping
    @PreAuthorize(PlatformPolicy.PLATFORM_ADMIN_ONLY)
    public StoreResponse create(@Valid @RequestBody RegisterStoreRequest request) {
        return storeService.create(request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(PlatformPolicy.PLATFORM_ADMIN_ONLY)
    public void delete(@PathVariable Long id, @RequestParam String confirmName) {
        storeDeletionService.deleteStore(id, confirmName);
    }

    @GetMapping("/current")
    @PreAuthorize("hasAuthority('STORE_VIEW')")
    public StoreResponse current() {
        return storeService.current();
    }

    @PutMapping("/current")
    @PreAuthorize("hasAuthority('STORE_MANAGE')")
    public StoreResponse updateCurrent(@Valid @RequestBody UpdateStoreRequest request) {
        return storeService.updateCurrent(request);
    }

    @GetMapping("/{id}")
    @PreAuthorize(PlatformPolicy.PLATFORM_ADMIN_ONLY)
    public StoreResponse get(@PathVariable Long id) {
        return storeService.getForPlatform(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(PlatformPolicy.PLATFORM_ADMIN_ONLY)
    public StoreResponse update(@PathVariable Long id, @Valid @RequestBody UpdateStoreRequest request) {
        return storeService.platformUpdate(id, request);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize(PlatformPolicy.PLATFORM_ADMIN_ONLY)
    public StoreResponse updateStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return storeService.updateStatus(id, request.status());
    }

    @GetMapping("/{id}/subscription")
    @PreAuthorize(PlatformPolicy.PLATFORM_ADMIN_ONLY)
    public SubscriptionSummaryResponse subscription(@PathVariable Long id) {
        return subscriptionService.platformStoreSummary(id);
    }

    @PutMapping("/{id}/subscription")
    @PreAuthorize(PlatformPolicy.PLATFORM_ADMIN_ONLY)
    public SubscriptionSummaryResponse updateSubscription(@PathVariable Long id,
                                                          @Valid @RequestBody SubscriptionRequest request) {
        return subscriptionService.updateForPlatform(id, request);
    }

    @GetMapping("/{id}/subscription/history")
    @PreAuthorize(PlatformPolicy.PLATFORM_ADMIN_ONLY)
    public List<SubscriptionHistoryResponse> subscriptionHistory(@PathVariable Long id) {
        return subscriptionService.history(id);
    }
}
