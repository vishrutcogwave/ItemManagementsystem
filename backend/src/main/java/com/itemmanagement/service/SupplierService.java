package com.itemmanagement.service;

import com.itemmanagement.dto.master.SupplierPurchaseResponse;
import com.itemmanagement.dto.master.SupplierResponse;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.Supplier;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.PurchaseRepository;
import com.itemmanagement.repository.SupplierRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final PurchaseRepository purchaseRepository;
    private final BranchRepository branchRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> list(String search, StoreStatus status, int page, int size) {
        Long storeId = TenantContext.storeId();
        Page<Supplier> suppliers = supplierRepository.search(storeId, status, clean(search),
                PageRequest.of(page, Math.min(Math.max(size, 1), 100)));
        return PageResponse.of(suppliers, this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<SupplierResponse> options() {
        Long storeId = TenantContext.storeId();
        return supplierRepository.findByStoreIdAndStatusOrderBySupplierName(storeId, StoreStatus.ACTIVE)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public SupplierResponse get(Long id) {
        return toResponse(find(id, TenantContext.storeId()));
    }

    @Transactional
    public SupplierResponse create(SupplierResponse.Request request) {
        Long storeId = TenantContext.storeId();
        Supplier supplier = supplierRepository.save(Supplier.builder()
                .storeId(storeId)
                .supplierName(request.supplierName().trim())
                .mobile(request.mobile().trim())
                .address(request.address().trim())
                .state(blankToNull(request.state()))
                .gstNumber(blankToNull(request.gstNumber()))
                .email(blankToNull(request.email()))
                .status(request.status() == null ? StoreStatus.ACTIVE : request.status())
                .build());
        auditService.log("CREATE", "SUPPLIER", supplier.getId(), "Created supplier " + supplier.getSupplierName());
        return toResponse(supplier);
    }

    @Transactional
    public SupplierResponse update(Long id, SupplierResponse.Request request) {
        Long storeId = TenantContext.storeId();
        Supplier supplier = find(id, storeId);
        supplier.setSupplierName(request.supplierName().trim());
        supplier.setMobile(request.mobile().trim());
        supplier.setAddress(request.address().trim());
        supplier.setState(blankToNull(request.state()));
        supplier.setGstNumber(blankToNull(request.gstNumber()));
        supplier.setEmail(blankToNull(request.email()));
        if (request.status() != null) {
            supplier.setStatus(request.status());
        }
        supplierRepository.save(supplier);
        auditService.log("UPDATE", "SUPPLIER", id, "Updated supplier " + supplier.getSupplierName());
        return toResponse(supplier);
    }

    @Transactional
    public SupplierResponse updateStatus(Long id, StoreStatus status) {
        Long storeId = TenantContext.storeId();
        Supplier supplier = find(id, storeId);
        supplier.setStatus(status);
        supplierRepository.save(supplier);
        auditService.log(status == StoreStatus.ACTIVE ? "ACTIVATE" : "DEACTIVATE", "SUPPLIER", id,
                "Supplier status changed to " + status);
        return toResponse(supplier);
    }

    @Transactional(readOnly = true)
    public List<SupplierPurchaseResponse> purchaseHistory(Long id) {
        Long storeId = TenantContext.storeId();
        Supplier supplier = find(id, storeId);
        AuthUser user = TenantContext.user();
        Map<Long, Branch> branches = branchRepository.findByStoreIdOrderByBranchName(storeId).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));
        return purchaseRepository.findTop20ByStoreIdAndSupplierIdOrderByCreatedAtDesc(storeId, supplier.getId())
                .stream()
                .filter(purchase -> user.canAccessBranch(purchase.getBranchId()))
                .map(purchase -> {
                    Branch branch = branches.get(purchase.getBranchId());
                    return new SupplierPurchaseResponse(purchase.getId(), purchase.getPurchaseNumber(),
                            purchase.getPurchaseDate(), branch == null ? "" : branch.getBranchName(),
                            purchase.getGrandTotal(), purchase.getStatus().name(), purchase.getCreatedAt());
                })
                .toList();
    }

    private Supplier find(Long id, Long storeId) {
        return supplierRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Supplier not found"));
    }

    private SupplierResponse toResponse(Supplier supplier) {
        return new SupplierResponse(supplier.getId(), supplier.getSupplierName(), supplier.getMobile(),
                supplier.getAddress(), supplier.getState(), supplier.getGstNumber(), supplier.getEmail(),
                supplier.getStatus().name(), supplier.getCreatedAt());
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
