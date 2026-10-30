package com.itemmanagement.controller;

import com.itemmanagement.dto.transaction.PurchaseRequest;
import com.itemmanagement.dto.transaction.PurchaseResponse;
import com.itemmanagement.entity.enums.PurchaseStatus;
import com.itemmanagement.service.PurchaseService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/purchases")
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService purchaseService;

    @GetMapping
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public PageResponse<PurchaseResponse> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) PurchaseStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return purchaseService.list(branchId, status, from, to, search, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public PurchaseResponse get(@PathVariable Long id) {
        return purchaseService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PURCHASE_MANAGE')")
    public PurchaseResponse create(@Valid @RequestBody PurchaseRequest request) {
        return purchaseService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_MANAGE')")
    public PurchaseResponse update(@PathVariable Long id,
                                   @Valid @RequestBody PurchaseRequest request) {
        return purchaseService.updateDraft(id, request);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('PURCHASE_MANAGE')")
    public PurchaseResponse complete(@PathVariable Long id) {
        return purchaseService.complete(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PURCHASE_MANAGE')")
    public PurchaseResponse cancel(@PathVariable Long id) {
        return purchaseService.cancel(id);
    }
}
