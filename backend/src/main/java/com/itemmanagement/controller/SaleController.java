package com.itemmanagement.controller;

import com.itemmanagement.dto.transaction.SaleCancellationRequest;
import com.itemmanagement.dto.transaction.SaleCancellationResponse;
import com.itemmanagement.dto.transaction.SaleModificationRequest;
import com.itemmanagement.dto.transaction.SaleModificationResponse;
import com.itemmanagement.dto.transaction.SaleRequest;
import com.itemmanagement.dto.transaction.SaleResponse;
import com.itemmanagement.entity.enums.SaleStatus;
import com.itemmanagement.service.SaleService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/sales")
@RequiredArgsConstructor
public class SaleController {

    private final SaleService saleService;

    @GetMapping
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    public PageResponse<SaleResponse> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) SaleStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return saleService.list(branchId, status, from, to, search, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    public SaleResponse get(@PathVariable Long id) {
        return saleService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SALES_MANAGE')")
    public SaleResponse create(@Valid @RequestBody SaleRequest request) {
        return saleService.create(request);
    }

    @PostMapping("/{id}/modifications/preview")
    @PreAuthorize("hasAuthority('SALES_MODIFY')")
    public SaleModificationResponse previewModification(@PathVariable Long id,
                                                        @Valid @RequestBody SaleModificationRequest request) {
        return saleService.previewModification(id, request);
    }

    @PostMapping("/{id}/modifications")
    @PreAuthorize("hasAuthority('SALES_MODIFY')")
    public SaleModificationResponse modify(@PathVariable Long id,
                                           @Valid @RequestBody SaleModificationRequest request) {
        return saleService.modify(id, request);
    }

    @GetMapping("/{id}/modifications")
    @PreAuthorize("hasAnyAuthority('SALES_VIEW', 'SALES_MODIFY')")
    public List<SaleModificationResponse> modifications(@PathVariable Long id) {
        return saleService.modifications(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SALES_CANCEL')")
    public SaleCancellationResponse cancel(@PathVariable Long id,
                                           @Valid @RequestBody SaleCancellationRequest request) {
        return saleService.cancel(id, request);
    }

    @GetMapping("/{id}/cancellation")
    @PreAuthorize("hasAnyAuthority('SALES_VIEW', 'SALES_CANCEL')")
    public SaleCancellationResponse cancellation(@PathVariable Long id) {
        return saleService.cancellation(id);
    }
}
