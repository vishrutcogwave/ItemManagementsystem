package com.itemmanagement.service;

import com.itemmanagement.dto.master.BranchResponse;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class BranchService {

    private final BranchRepository branchRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<BranchResponse> listAccessible() {
        Long storeId = TenantContext.storeId();
        AuthUser user = TenantContext.user();
        List<Branch> branches = branchRepository.findByStoreIdOrderByBranchName(storeId);
        return branches.stream()
                .filter(branch -> user.canAccessBranch(branch.getId()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BranchResponse> listAll() {
        Long storeId = TenantContext.storeId();
        return branchRepository.findByStoreIdOrderByBranchName(storeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BranchResponse get(Long id) {
        Long storeId = TenantContext.storeId();
        Branch branch = find(id, storeId);
        TenantContext.requireBranch(id);
        return toResponse(branch);
    }

    @Transactional
    public BranchResponse create(BranchResponse.Request request) {
        Long storeId = TenantContext.storeId();
        String code = request.branchCode().trim().toUpperCase(Locale.ROOT);
        if (branchRepository.existsByStoreIdAndBranchCodeIgnoreCase(storeId, code)) {
            throw ApiException.conflict("Branch code already exists in this store");
        }
        Branch branch = branchRepository.save(Branch.builder()
                .storeId(storeId)
                .branchCode(code)
                .branchName(request.branchName().trim())
                .address(request.address().trim())
                .contactNumber(request.contactNumber().trim())
                .state(blankToNull(request.state()))
                .status(request.status() == null ? StoreStatus.ACTIVE : request.status())
                .build());
        auditService.log("CREATE", "BRANCH", branch.getId(), "Created branch " + branch.getBranchName());
        return toResponse(branch);
    }

    @Transactional
    public BranchResponse update(Long id, BranchResponse.Request request) {
        Long storeId = TenantContext.storeId();
        Branch branch = find(id, storeId);
        String code = request.branchCode().trim().toUpperCase(Locale.ROOT);
        if (!branch.getBranchCode().equalsIgnoreCase(code)
                && branchRepository.existsByStoreIdAndBranchCodeIgnoreCase(storeId, code)) {
            throw ApiException.conflict("Branch code already exists in this store");
        }
        branch.setBranchCode(code);
        branch.setBranchName(request.branchName().trim());
        branch.setAddress(request.address().trim());
        branch.setContactNumber(request.contactNumber().trim());
        branch.setState(blankToNull(request.state()));
        if (request.status() != null) {
            branch.setStatus(request.status());
        }
        branchRepository.save(branch);
        auditService.log("UPDATE", "BRANCH", id, "Updated branch " + branch.getBranchName());
        return toResponse(branch);
    }

    @Transactional
    public BranchResponse updateStatus(Long id, StoreStatus status) {
        Long storeId = TenantContext.storeId();
        Branch branch = find(id, storeId);
        branch.setStatus(status);
        branchRepository.save(branch);
        auditService.log(status == StoreStatus.ACTIVE ? "ACTIVATE" : "DEACTIVATE", "BRANCH", id,
                "Branch status changed to " + status);
        return toResponse(branch);
    }

    public Branch find(Long id, Long storeId) {
        return branchRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
    }

    public BranchResponse toResponse(Branch branch) {
        return new BranchResponse(branch.getId(), branch.getStoreId(), branch.getBranchCode(),
                branch.getBranchName(), branch.getAddress(), branch.getContactNumber(),
                branch.getState(), branch.getStatus().name(), branch.getCreatedAt());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
