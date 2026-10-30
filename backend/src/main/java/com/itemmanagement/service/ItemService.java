package com.itemmanagement.service;

import com.itemmanagement.dto.inventory.BranchStockResponse;
import com.itemmanagement.dto.inventory.InventoryTransactionResponse;
import com.itemmanagement.dto.inventory.PosItemResponse;
import com.itemmanagement.dto.master.ItemResponse;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.BranchInventory;
import com.itemmanagement.entity.Category;
import com.itemmanagement.entity.InventoryTransaction;
import com.itemmanagement.entity.Item;
import com.itemmanagement.entity.Store;
import com.itemmanagement.entity.TaxMaster;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchInventoryRepository;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.CategoryRepository;
import com.itemmanagement.repository.InventoryTransactionRepository;
import com.itemmanagement.repository.ItemRepository;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.repository.TaxMasterRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final TaxMasterRepository taxMasterRepository;
    private final StoreRepository storeRepository;
    private final BranchRepository branchRepository;
    private final BranchInventoryRepository inventoryRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<ItemResponse> list(String search, Long categoryId, StoreStatus status,
                                           int page, int size) {
        Long storeId = TenantContext.storeId();
        Map<Long, Category> categories = categories(storeId);
        Map<Long, TaxMaster> taxes = taxes(storeId);
        Page<Item> items = itemRepository.search(storeId, categoryId, status, clean(search),
                PageRequest.of(page, Math.min(Math.max(size, 1), 100)));
        return PageResponse.of(items, item -> toResponse(item, categories, taxes));
    }

    @Transactional(readOnly = true)
    public List<ItemResponse> options(Long categoryId) {
        Long storeId = TenantContext.storeId();
        Map<Long, Category> categories = categories(storeId);
        Map<Long, TaxMaster> taxes = taxes(storeId);
        List<Item> items = categoryId == null
                ? itemRepository.findByStoreIdAndStatusOrderByItemName(storeId, StoreStatus.ACTIVE)
                : itemRepository.findByStoreIdAndCategoryIdOrderByItemName(storeId, categoryId).stream()
                .filter(item -> item.getStatus() == StoreStatus.ACTIVE).toList();
        return items.stream().map(item -> toResponse(item, categories, taxes)).toList();
    }

    @Transactional(readOnly = true)
    public ItemResponse get(Long id) {
        Long storeId = TenantContext.storeId();
        Item item = find(id, storeId);
        return toResponse(item, categories(storeId), taxes(storeId));
    }

    @Transactional
    public ItemResponse create(ItemResponse.Request request) {
        Long storeId = TenantContext.storeId();
        Category category = category(request.categoryId(), storeId);
        TaxMaster tax = tax(request.taxId(), storeId, null);
        String code = request.itemCode().trim().toUpperCase();
        if (itemRepository.existsByStoreIdAndItemCodeIgnoreCase(storeId, code)) {
            throw ApiException.conflict("An item with this code already exists");
        }
        Item item = itemRepository.save(Item.builder()
                .storeId(storeId)
                .categoryId(category.getId())
                .taxId(tax.getId())
                .itemCode(code)
                .itemName(request.itemName().trim())
                .baseUnit(request.baseUnit())
                .rate(request.rate())
                .minStockLevel(request.minStockLevel())
                .status(request.status() == null ? StoreStatus.ACTIVE : request.status())
                .build());
        auditService.log("CREATE", "ITEM", item.getId(), "Created item " + item.getItemName());
        return toResponse(item, Map.of(category.getId(), category), Map.of(tax.getId(), tax));
    }

    @Transactional
    public ItemResponse update(Long id, ItemResponse.Request request) {
        Long storeId = TenantContext.storeId();
        Item item = find(id, storeId);
        Category category = category(request.categoryId(), storeId);
        TaxMaster tax = tax(request.taxId(), storeId, item.getTaxId());
        String code = request.itemCode().trim().toUpperCase();
        if (!item.getItemCode().equalsIgnoreCase(code)
                && itemRepository.existsByStoreIdAndItemCodeIgnoreCase(storeId, code)) {
            throw ApiException.conflict("An item with this code already exists");
        }
        item.setCategoryId(category.getId());
        item.setTaxId(tax.getId());
        item.setItemCode(code);
        item.setItemName(request.itemName().trim());
        item.setBaseUnit(request.baseUnit());
        item.setRate(request.rate());
        item.setMinStockLevel(request.minStockLevel());
        if (request.status() != null) {
            item.setStatus(request.status());
        }
        itemRepository.save(item);
        auditService.log("UPDATE", "ITEM", id, "Updated item " + item.getItemName());
        return toResponse(item, Map.of(category.getId(), category), Map.of(tax.getId(), tax));
    }

    @Transactional
    public ItemResponse updateStatus(Long id, StoreStatus status) {
        Long storeId = TenantContext.storeId();
        Item item = find(id, storeId);
        item.setStatus(status);
        itemRepository.save(item);
        auditService.log(status == StoreStatus.ACTIVE ? "ACTIVATE" : "DEACTIVATE", "ITEM", id,
                "Item status changed to " + status);
        return toResponse(item, categories(storeId), taxes(storeId));
    }

    @Transactional(readOnly = true)
    public List<BranchStockResponse> stock(Long itemId) {
        Long storeId = TenantContext.storeId();
        Item item = find(itemId, storeId);
        AuthUser user = TenantContext.user();
        List<Branch> branches = branchRepository.findByStoreIdAndStatusOrderByBranchName(storeId, StoreStatus.ACTIVE)
                .stream().filter(branch -> user.canAccessBranch(branch.getId())).toList();
        Map<Long, BranchInventory> inventories = inventoryRepository.findByStoreIdAndItemId(storeId, itemId)
                .stream().collect(Collectors.toMap(BranchInventory::getBranchId, Function.identity()));
        return branches.stream().map(branch -> {
            BranchInventory inv = inventories.get(branch.getId());
            BigDecimal stock = inv == null ? BigDecimal.ZERO : inv.getCurrentStock();
            BigDecimal min = inv == null ? item.getMinStockLevel() : inv.getMinimumStock();
            return new BranchStockResponse(branch.getId(), branch.getBranchCode(), branch.getBranchName(),
                    stock, min,
                    inv == null ? BigDecimal.ZERO : inv.getPurchasePrice(),
                    inv == null ? item.getRate() : inv.getSellingPrice(),
                    item.getBaseUnit().name(), stock.compareTo(min) <= 0);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionResponse> transactions(Long itemId, Long branchId) {
        Long storeId = TenantContext.storeId();
        Item item = find(itemId, storeId);
        if (branchId != null) {
            TenantContext.requireBranch(branchId);
        }
        AuthUser user = TenantContext.user();
        Map<Long, Branch> branches = branchRepository.findByStoreIdOrderByBranchName(storeId).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));
        List<InventoryTransaction> transactions = branchId == null
                ? transactionRepository.findByStoreIdAndItemIdOrderByTransactionDateDesc(storeId, itemId)
                : transactionRepository.findByStoreIdAndBranchIdAndItemIdOrderByTransactionDateDesc(
                storeId, branchId, itemId);
        return transactions.stream()
                .filter(t -> user.canAccessBranch(t.getBranchId()))
                .limit(200)
                .map(t -> new InventoryTransactionResponse(t.getId(), t.getBranchId(),
                        branches.containsKey(t.getBranchId()) ? branches.get(t.getBranchId()).getBranchName() : "",
                        item.getId(), item.getItemName(), t.getTransactionType().name(), t.getReferenceType(),
                        t.getReferenceNumber(), t.getQuantityIn(), t.getQuantityOut(), t.getBalanceAfter(),
                        item.getBaseUnit().name(), t.getNotes(), t.getTransactionDate()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PosItemResponse> posItems(Long branchId, Long categoryId, String search) {
        Long storeId = TenantContext.storeId();
        TenantContext.requireBranch(branchId);
        Branch branch = branchRepository.findByIdAndStoreId(branchId, storeId)
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
        if (branch.getStatus() != StoreStatus.ACTIVE) {
            throw ApiException.badRequest("This branch is inactive");
        }
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> ApiException.notFound("Store not found"));
        Map<Long, Category> categories = categories(storeId);
        Map<Long, TaxMaster> taxes = taxes(storeId);
        Map<Long, BranchInventory> inventories = inventoryRepository
                .findByStoreIdAndBranchId(storeId, branchId).stream()
                .collect(Collectors.toMap(BranchInventory::getItemId, Function.identity()));
        String needle = search == null ? null : search.trim().toLowerCase();
        return itemRepository.findByStoreIdAndStatusOrderByItemName(storeId, StoreStatus.ACTIVE).stream()
                .filter(item -> categoryId == null || item.getCategoryId().equals(categoryId))
                .filter(item -> needle == null || item.getItemName().toLowerCase().contains(needle)
                        || item.getItemCode().toLowerCase().contains(needle))
                .map(item -> {
                    BranchInventory inv = inventories.get(item.getId());
                    BigDecimal stock = inv == null ? BigDecimal.ZERO : inv.getCurrentStock();
                    BigDecimal min = inv == null ? item.getMinStockLevel() : inv.getMinimumStock();
                    BigDecimal price = item.getRate();
                    Category category = categories.get(item.getCategoryId());
                    TaxMaster tax = taxes.get(item.getTaxId());
                    return new PosItemResponse(item.getId(), item.getItemCode(), item.getItemName(),
                            item.getCategoryId(), category == null ? "" : category.getCategoryName(),
                            item.getBaseUnit().name(), price, stock, stock.compareTo(min) <= 0,
                            item.getTaxId(), tax == null ? "" : tax.getTaxName(),
                            tax == null ? BigDecimal.ZERO : tax.getGstPercentage(),
                            tax == null ? "EXEMPT" : tax.getTaxType().name(), store.isTaxInclusive());
                })
                .sorted(Comparator.comparing(PosItemResponse::itemName))
                .toList();
    }

    public Item find(Long id, Long storeId) {
        return itemRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Item not found"));
    }

    private Category category(Long categoryId, Long storeId) {
        return categoryRepository.findByIdAndStoreId(categoryId, storeId)
                .orElseThrow(() -> ApiException.badRequest("Select a valid category"));
    }

    private TaxMaster tax(Long taxId, Long storeId, Long currentTaxId) {
        TaxMaster tax = taxMasterRepository.findByIdAndStoreId(taxId, storeId)
                .orElseThrow(() -> ApiException.badRequest("Select a valid tax"));
        if (tax.getStatus() != StoreStatus.ACTIVE && !tax.getId().equals(currentTaxId)) {
            throw ApiException.badRequest("The selected tax is inactive");
        }
        return tax;
    }

    private Map<Long, Category> categories(Long storeId) {
        return categoryRepository.findByStoreIdOrderByCategoryName(storeId).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
    }

    private Map<Long, TaxMaster> taxes(Long storeId) {
        return taxMasterRepository.findByStoreIdOrderByGstPercentageAscTaxNameAsc(storeId).stream()
                .collect(Collectors.toMap(TaxMaster::getId, Function.identity()));
    }

    private ItemResponse toResponse(Item item, Map<Long, Category> categories,
                                    Map<Long, TaxMaster> taxes) {
        Category category = categories.get(item.getCategoryId());
        TaxMaster tax = taxes.get(item.getTaxId());
        return new ItemResponse(item.getId(), item.getItemCode(), item.getItemName(), item.getCategoryId(),
                category == null ? "" : category.getCategoryName(),
                item.getTaxId(), tax == null ? "" : tax.getTaxName(),
                tax == null ? BigDecimal.ZERO : tax.getGstPercentage(),
                tax == null ? "EXEMPT" : tax.getTaxType().name(),
                item.getBaseUnit().name(),
                item.getRate(), item.getMinStockLevel(),
                item.getStatus().name(), item.getCreatedAt());
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
