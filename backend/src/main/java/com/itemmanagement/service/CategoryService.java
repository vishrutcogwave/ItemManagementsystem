package com.itemmanagement.service;

import com.itemmanagement.dto.master.CategoryRequest;
import com.itemmanagement.dto.master.CategoryResponse;
import com.itemmanagement.entity.Category;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.CategoryRepository;
import com.itemmanagement.repository.ItemRepository;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PageResponse;
import com.itemmanagement.util.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ItemRepository itemRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<CategoryResponse> list(String search, StoreStatus status, int page, int size) {
        Long storeId = TenantContext.storeId();
        Page<Category> categories = categoryRepository.search(storeId, clean(search), status,
                Pagination.of(page, size));
        return PageResponse.of(categories, category -> toResponse(storeId, category));
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> options() {
        Long storeId = TenantContext.storeId();
        return categoryRepository.findByStoreIdAndStatusOrderByCategoryName(storeId, StoreStatus.ACTIVE)
                .stream().map(category -> toResponse(storeId, category)).toList();
    }

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        Long storeId = TenantContext.storeId();
        String name = request.categoryName().trim();
        if (categoryRepository.existsByStoreIdAndCategoryNameIgnoreCase(storeId, name)) {
            throw ApiException.conflict("A category with this name already exists");
        }
        Category category = categoryRepository.save(Category.builder()
                .storeId(storeId)
                .categoryName(name)
                .description(blankToNull(request.description()))
                .status(request.status() == null ? StoreStatus.ACTIVE : request.status())
                .build());
        auditService.log("CREATE", "CATEGORY", category.getId(), "Created category " + name);
        return toResponse(storeId, category);
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request) {
        Long storeId = TenantContext.storeId();
        Category category = find(id, storeId);
        String name = request.categoryName().trim();
        if (!category.getCategoryName().equalsIgnoreCase(name)
                && categoryRepository.existsByStoreIdAndCategoryNameIgnoreCase(storeId, name)) {
            throw ApiException.conflict("A category with this name already exists");
        }
        category.setCategoryName(name);
        category.setDescription(blankToNull(request.description()));
        if (request.status() != null) {
            category.setStatus(request.status());
        }
        categoryRepository.save(category);
        auditService.log("UPDATE", "CATEGORY", id, "Updated category " + name);
        return toResponse(storeId, category);
    }

    @Transactional
    public void delete(Long id) {
        Long storeId = TenantContext.storeId();
        Category category = find(id, storeId);
        long items = itemRepository.countByStoreIdAndCategoryId(storeId, id);
        if (items > 0) {
            throw ApiException.badRequest("This category is used by " + items
                    + " item(s). Move those items to another category before deleting.");
        }
        categoryRepository.delete(category);
        auditService.log("DELETE", "CATEGORY", id, "Deleted category " + category.getCategoryName());
    }

    @Transactional
    public CategoryResponse updateStatus(Long id, StoreStatus status) {
        Long storeId = TenantContext.storeId();
        Category category = find(id, storeId);
        category.setStatus(status);
        categoryRepository.save(category);
        auditService.log(status == StoreStatus.ACTIVE ? "ACTIVATE" : "DEACTIVATE", "CATEGORY", id,
                "Category status changed to " + status);
        return toResponse(storeId, category);
    }

    public Category find(Long id, Long storeId) {
        return categoryRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Category not found"));
    }

    private CategoryResponse toResponse(Long storeId, Category category) {
        return new CategoryResponse(category.getId(), category.getCategoryName(),
                category.getDescription(), category.getStatus().name(),
                itemRepository.countByStoreIdAndCategoryId(storeId, category.getId()),
                category.getCreatedAt());
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
