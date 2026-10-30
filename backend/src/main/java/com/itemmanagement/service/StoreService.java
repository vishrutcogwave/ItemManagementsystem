package com.itemmanagement.service;

import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.dto.master.StoreResponse;
import com.itemmanagement.dto.master.UpdateStoreRequest;
import com.itemmanagement.entity.Store;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.repository.UserRepository;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final StoreProvisioningService provisioningService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<StoreResponse> list(String search, StoreStatus status, int page, int size) {
        Page<Store> stores = storeRepository.search(clean(search), status,
                PageRequest.of(page, Math.min(Math.max(size, 1), 100)));
        return PageResponse.of(stores, this::toResponse);
    }

    @Transactional
    public StoreResponse create(RegisterStoreRequest request) {
        StoreProvisioningService.ProvisionedStore provisioned = provisioningService.createStore(request, true);
        auditService.log("CREATE", "STORE", provisioned.store().getId(),
                "Created store " + provisioned.store().getStoreName());
        return toResponse(provisioned.store());
    }

    @Transactional(readOnly = true)
    public StoreResponse getForPlatform(Long id) {
        return toResponse(find(id));
    }

    @Transactional
    public StoreResponse platformUpdate(Long id, UpdateStoreRequest request) {
        Store store = find(id);
        apply(store, request);
        return toResponse(storeRepository.save(store));
    }

    @Transactional
    public StoreResponse updateStatus(Long id, StoreStatus status) {
        Store store = find(id);
        store.setStatus(status);
        storeRepository.save(store);
        auditService.log(status == StoreStatus.ACTIVE ? "ACTIVATE" : "DEACTIVATE", "STORE", id,
                "Store status changed to " + status);
        return toResponse(store);
    }

    @Transactional(readOnly = true)
    public StoreResponse current() {
        Long storeId = TenantContext.storeId();
        return toResponse(find(storeId));
    }

    @Transactional
    public StoreResponse updateCurrent(UpdateStoreRequest request) {
        Long storeId = TenantContext.storeId();
        Store store = find(storeId);
        apply(store, request);
        storeRepository.save(store);
        auditService.log("UPDATE", "STORE", storeId, "Store details updated");
        return toResponse(store);
    }

    private void apply(Store store, UpdateStoreRequest request) {
        store.setStoreName(request.storeName().trim());
        store.setOwnerName(request.ownerName().trim());
        store.setEmail(request.email().trim());
        store.setMobile(request.mobile().trim());
        store.setAddress(request.address().trim());
        store.setGstNumber(request.gstNumber() == null || request.gstNumber().isBlank()
                ? null : request.gstNumber().trim().toUpperCase(Locale.ROOT));
        if (request.taxInclusive() != null) {
            store.setTaxInclusive(request.taxInclusive());
        }
    }

    private Store find(Long id) {
        return storeRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Store not found"));
    }

    private StoreResponse toResponse(Store store) {
        return new StoreResponse(
                store.getId(), store.getStoreCode(), store.getStoreName(), store.getOwnerName(),
                store.getEmail(), store.getMobile(), store.getAddress(), store.getGstNumber(),
                store.isTaxInclusive(), store.getStatus().name(), store.getCreatedAt(),
                branchRepository.countByStoreId(store.getId()),
                userRepository.countByStoreId(store.getId())
        );
    }

    private String clean(String search) {
        return search == null || search.isBlank() ? "" : search.trim();
    }
}
