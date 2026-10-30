package com.itemmanagement.controller;

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

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SALES_MANAGE')")
    public SaleResponse cancel(@PathVariable Long id) {
        return saleService.cancel(id);
    }
}
