package com.itemmanagement.controller;

import com.itemmanagement.dto.common.StatusRequest;
import com.itemmanagement.dto.master.BranchResponse;
import com.itemmanagement.service.BranchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/branches")
@RequiredArgsConstructor
public class BranchController {

    private final BranchService branchService;

    @GetMapping
    @PreAuthorize("hasAuthority('BRANCH_VIEW')")
    public List<BranchResponse> list() {
        return branchService.listAccessible();
    }

    @GetMapping("/my-branches")
    public List<BranchResponse> myBranches() {
        return branchService.listAccessible();
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('BRANCH_MANAGE')")
    public List<BranchResponse> all() {
        return branchService.listAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('BRANCH_VIEW')")
    public BranchResponse get(@PathVariable Long id) {
        return branchService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('BRANCH_MANAGE')")
    public BranchResponse create(@Valid @RequestBody BranchResponse.Request request) {
        return branchService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('BRANCH_MANAGE')")
    public BranchResponse update(@PathVariable Long id,
                                 @Valid @RequestBody BranchResponse.Request request) {
        return branchService.update(id, request);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('BRANCH_MANAGE')")
    public BranchResponse updateStatus(@PathVariable Long id,
                                       @Valid @RequestBody StatusRequest request) {
        return branchService.updateStatus(id, request.status());
    }
}
