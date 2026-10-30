package com.itemmanagement.controller;

import com.itemmanagement.dto.common.StatusRequest;
import com.itemmanagement.dto.inventory.BranchStockResponse;
import com.itemmanagement.dto.inventory.InventoryTransactionResponse;
import com.itemmanagement.dto.inventory.PosItemResponse;
import com.itemmanagement.dto.master.ItemResponse;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.service.ItemService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;

    @GetMapping
    @PreAuthorize("hasAuthority('ITEM_VIEW')")
    public PageResponse<ItemResponse> list(@RequestParam(required = false) String search,
                                           @RequestParam(required = false) Long categoryId,
                                           @RequestParam(required = false) StoreStatus status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return itemService.list(search, categoryId, status, page, size);
    }

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('ITEM_VIEW')")
    public List<ItemResponse> options(@RequestParam(required = false) Long categoryId) {
        return itemService.options(categoryId);
    }

    @GetMapping("/pos")
    @PreAuthorize("hasAuthority('SALES_MANAGE') or hasAuthority('SALES_VIEW')")
    public List<PosItemResponse> posItems(@RequestParam Long branchId,
                                          @RequestParam(required = false) Long categoryId,
                                          @RequestParam(required = false) String search) {
        return itemService.posItems(branchId, categoryId, search);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ITEM_VIEW')")
    public ItemResponse get(@PathVariable Long id) {
        return itemService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ITEM_MANAGE')")
    public ItemResponse create(@Valid @RequestBody ItemResponse.Request request) {
        return itemService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ITEM_MANAGE')")
    public ItemResponse update(@PathVariable Long id, @Valid @RequestBody ItemResponse.Request request) {
        return itemService.update(id, request);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ITEM_MANAGE')")
    public ItemResponse updateStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return itemService.updateStatus(id, request.status());
    }

    @GetMapping("/{id}/stock")
    @PreAuthorize("hasAuthority('ITEM_VIEW')")
    public List<BranchStockResponse> stock(@PathVariable Long id) {
        return itemService.stock(id);
    }

    @GetMapping("/{id}/transactions")
    @PreAuthorize("hasAuthority('ITEM_VIEW') or hasAuthority('INVENTORY_VIEW')")
    public List<InventoryTransactionResponse> transactions(@PathVariable Long id,
                                                           @RequestParam(required = false) Long branchId) {
        return itemService.transactions(id, branchId);
    }
}
