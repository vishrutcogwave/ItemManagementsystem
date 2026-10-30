package com.itemmanagement.controller;

import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.dto.common.StatusRequest;
import com.itemmanagement.dto.master.StoreResponse;
import com.itemmanagement.dto.master.UpdateStoreRequest;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.service.StoreService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @GetMapping
    @PreAuthorize("hasAuthority('PLATFORM_MANAGE')")
    public PageResponse<StoreResponse> list(@RequestParam(required = false) String search,
                                            @RequestParam(required = false) StoreStatus status,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        return storeService.list(search, status, page, size);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PLATFORM_MANAGE')")
    public StoreResponse create(@Valid @RequestBody RegisterStoreRequest request) {
        return storeService.create(request);
    }

    @GetMapping("/current")
    @PreAuthorize("hasAuthority('STORE_VIEW')")
    public StoreResponse current() {
        return storeService.current();
    }

    @PutMapping("/current")
    @PreAuthorize("hasAuthority('STORE_MANAGE')")
    public StoreResponse updateCurrent(@Valid @RequestBody UpdateStoreRequest request) {
        return storeService.updateCurrent(request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PLATFORM_MANAGE')")
    public StoreResponse get(@PathVariable Long id) {
        return storeService.getForPlatform(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PLATFORM_MANAGE')")
    public StoreResponse update(@PathVariable Long id, @Valid @RequestBody UpdateStoreRequest request) {
        return storeService.platformUpdate(id, request);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PLATFORM_MANAGE')")
    public StoreResponse updateStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return storeService.updateStatus(id, request.status());
    }
}
