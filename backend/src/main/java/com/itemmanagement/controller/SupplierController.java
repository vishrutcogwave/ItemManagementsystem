package com.itemmanagement.controller;

import com.itemmanagement.dto.common.StatusRequest;
import com.itemmanagement.dto.master.SupplierPurchaseResponse;
import com.itemmanagement.dto.master.SupplierResponse;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.service.SupplierService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @GetMapping
    @PreAuthorize("hasAuthority('SUPPLIER_VIEW')")
    public PageResponse<SupplierResponse> list(@RequestParam(required = false) String search,
                                               @RequestParam(required = false) StoreStatus status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        return supplierService.list(search, status, page, size);
    }

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('SUPPLIER_VIEW')")
    public List<SupplierResponse> options() {
        return supplierService.options();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPPLIER_VIEW')")
    public SupplierResponse get(@PathVariable Long id) {
        return supplierService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SUPPLIER_MANAGE')")
    public SupplierResponse create(@Valid @RequestBody SupplierResponse.Request request) {
        return supplierService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPPLIER_MANAGE')")
    public SupplierResponse update(@PathVariable Long id,
                                   @Valid @RequestBody SupplierResponse.Request request) {
        return supplierService.update(id, request);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('SUPPLIER_MANAGE')")
    public SupplierResponse updateStatus(@PathVariable Long id,
                                         @Valid @RequestBody StatusRequest request) {
        return supplierService.updateStatus(id, request.status());
    }

    @GetMapping("/{id}/purchases")
    @PreAuthorize("hasAuthority('SUPPLIER_VIEW')")
    public List<SupplierPurchaseResponse> purchaseHistory(@PathVariable Long id) {
        return supplierService.purchaseHistory(id);
    }
}
