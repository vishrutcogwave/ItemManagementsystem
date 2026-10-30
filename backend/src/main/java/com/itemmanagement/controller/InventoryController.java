package com.itemmanagement.controller;

import com.itemmanagement.dto.inventory.InventoryPricingRequest;
import com.itemmanagement.dto.inventory.InventoryRowResponse;
import com.itemmanagement.dto.inventory.InventoryTransactionResponse;
import com.itemmanagement.dto.inventory.StockAdjustmentRequest;
import com.itemmanagement.service.InventoryService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public PageResponse<InventoryRowResponse> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean lowOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return inventoryService.list(branchId, categoryId, search, lowOnly, page, size);
    }

    @GetMapping("/low-stock")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public List<InventoryRowResponse> lowStock(@RequestParam(required = false) Long branchId,
                                               @RequestParam(defaultValue = "50") int limit) {
        return inventoryService.lowStock(branchId, Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public PageResponse<InventoryTransactionResponse> transactions(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long itemId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return inventoryService.transactions(branchId, itemId, search, page, size);
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasAuthority('INVENTORY_ADJUST')")
    public InventoryRowResponse adjust(@Valid @RequestBody StockAdjustmentRequest request) {
        return inventoryService.adjust(request);
    }

    @PutMapping("/{branchId}/items/{itemId}/pricing")
    @PreAuthorize("hasAuthority('INVENTORY_ADJUST')")
    public InventoryRowResponse updatePricing(@PathVariable Long branchId, @PathVariable Long itemId,
                                              @Valid @RequestBody InventoryPricingRequest request) {
        return inventoryService.updatePricing(branchId, itemId, request.minimumStock(),
                request.purchasePrice(), request.sellingPrice());
    }
}
