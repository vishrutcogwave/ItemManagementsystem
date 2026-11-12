package com.itemmanagement.controller;

import com.itemmanagement.dto.supplier.SupplierBillResponse;
import com.itemmanagement.dto.supplier.SupplierOutstandingResponse;
import com.itemmanagement.dto.supplier.SupplierPaymentRequest;
import com.itemmanagement.dto.supplier.SupplierPaymentResponse;
import com.itemmanagement.service.SupplierPaymentService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/supplier-payments")
@RequiredArgsConstructor
public class SupplierPaymentController {

    private final SupplierPaymentService supplierPaymentService;

    @GetMapping("/outstanding")
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public PageResponse<SupplierOutstandingResponse> outstanding(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return supplierPaymentService.outstanding(search, branchId, page, size);
    }

    @GetMapping("/suppliers/{supplierId}/bills")
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public List<SupplierBillResponse> bills(@PathVariable Long supplierId,
                                            @RequestParam(required = false) Long branchId) {
        return supplierPaymentService.bills(supplierId, branchId);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public PageResponse<SupplierPaymentResponse> list(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return supplierPaymentService.list(supplierId, from, to, search, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_VIEW')")
    public SupplierPaymentResponse get(@PathVariable Long id) {
        return supplierPaymentService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PURCHASE_MANAGE')")
    public SupplierPaymentResponse create(@Valid @RequestBody SupplierPaymentRequest request) {
        return supplierPaymentService.create(request);
    }
}
