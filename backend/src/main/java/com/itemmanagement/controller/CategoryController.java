package com.itemmanagement.controller;

import com.itemmanagement.dto.common.StatusRequest;
import com.itemmanagement.dto.master.CategoryRequest;
import com.itemmanagement.dto.master.CategoryResponse;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.service.CategoryService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @PreAuthorize("hasAuthority('CATEGORY_VIEW')")
    public PageResponse<CategoryResponse> list(@RequestParam(required = false) String search,
                                               @RequestParam(required = false) StoreStatus status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        return categoryService.list(search, status, page, size);
    }

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('CATEGORY_VIEW')")
    public List<CategoryResponse> options() {
        return categoryService.options();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CATEGORY_MANAGE')")
    public CategoryResponse create(@Valid @RequestBody CategoryRequest request) {
        return categoryService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CATEGORY_MANAGE')")
    public CategoryResponse update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('CATEGORY_MANAGE')")
    public void delete(@PathVariable Long id) {
        categoryService.delete(id);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('CATEGORY_MANAGE')")
    public CategoryResponse updateStatus(@PathVariable Long id,
                                         @Valid @RequestBody StatusRequest request) {
        return categoryService.updateStatus(id, request.status());
    }
}
