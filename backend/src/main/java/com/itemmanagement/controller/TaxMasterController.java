package com.itemmanagement.controller;

import com.itemmanagement.dto.common.StatusRequest;
import com.itemmanagement.dto.master.TaxMasterRequest;
import com.itemmanagement.dto.master.TaxMasterResponse;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.service.TaxMasterService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/taxes")
@RequiredArgsConstructor
public class TaxMasterController {

    private final TaxMasterService taxMasterService;

    @GetMapping
    @PreAuthorize("hasAuthority('TAX_VIEW')")
    public PageResponse<TaxMasterResponse> list(@RequestParam(required = false) String search,
                                                @RequestParam(required = false) StoreStatus status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        return taxMasterService.list(search, status, page, size);
    }

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('TAX_VIEW') or hasAuthority('ITEM_VIEW')")
    public List<TaxMasterResponse> options() {
        return taxMasterService.options();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TAX_VIEW')")
    public TaxMasterResponse get(@PathVariable Long id) {
        return taxMasterService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('TAX_MANAGE')")
    public TaxMasterResponse create(@Valid @RequestBody TaxMasterRequest request) {
        return taxMasterService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('TAX_MANAGE')")
    public TaxMasterResponse update(@PathVariable Long id, @Valid @RequestBody TaxMasterRequest request) {
        return taxMasterService.update(id, request);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('TAX_MANAGE')")
    public TaxMasterResponse updateStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return taxMasterService.updateStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('TAX_MANAGE')")
    public void delete(@PathVariable Long id) {
        taxMasterService.delete(id);
    }
}
