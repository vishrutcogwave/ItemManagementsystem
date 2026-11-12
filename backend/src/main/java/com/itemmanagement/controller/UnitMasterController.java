package com.itemmanagement.controller;

import com.itemmanagement.dto.common.StatusRequest;
import com.itemmanagement.dto.master.UnitMasterRequest;
import com.itemmanagement.dto.master.UnitMasterResponse;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.service.UnitMasterService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/units")
@RequiredArgsConstructor
public class UnitMasterController {

    private final UnitMasterService unitMasterService;

    @GetMapping
    @PreAuthorize("hasAuthority('UNIT_VIEW')")
    public PageResponse<UnitMasterResponse> list(@RequestParam(required = false) String search,
                                                 @RequestParam(required = false) StoreStatus status,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "10") int size) {
        return unitMasterService.list(search, status, page, size);
    }

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('UNIT_VIEW') or hasAuthority('ITEM_VIEW')")
    public List<UnitMasterResponse> options() {
        return unitMasterService.options();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('UNIT_VIEW')")
    public UnitMasterResponse get(@PathVariable Long id) {
        return unitMasterService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('UNIT_MANAGE')")
    public UnitMasterResponse create(@Valid @RequestBody UnitMasterRequest request) {
        return unitMasterService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UNIT_MANAGE')")
    public UnitMasterResponse update(@PathVariable Long id, @Valid @RequestBody UnitMasterRequest request) {
        return unitMasterService.update(id, request);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('UNIT_MANAGE')")
    public UnitMasterResponse updateStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return unitMasterService.updateStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('UNIT_MANAGE')")
    public void delete(@PathVariable Long id) {
        unitMasterService.delete(id);
    }
}
