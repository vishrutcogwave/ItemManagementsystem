package com.itemmanagement.service;

import com.itemmanagement.dto.inventory.InventoryRowResponse;
import com.itemmanagement.dto.inventory.InventoryTransactionResponse;
import com.itemmanagement.dto.inventory.StockAdjustmentRequest;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.BranchInventory;
import com.itemmanagement.entity.Category;
import com.itemmanagement.entity.InventoryTransaction;
import com.itemmanagement.entity.Item;
import com.itemmanagement.entity.StockAdjustment;
import com.itemmanagement.entity.enums.AdjustmentType;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.entity.enums.TransactionType;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchInventoryRepository;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.CategoryRepository;
import com.itemmanagement.repository.InventoryTransactionRepository;
import com.itemmanagement.repository.ItemRepository;
import com.itemmanagement.repository.StockAdjustmentRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PageResponse;
import com.itemmanagement.util.Pagination;
import com.itemmanagement.util.UnitConverter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final BranchInventoryRepository inventoryRepository;
    private final BranchRepository branchRepository;
    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final StockAdjustmentRepository adjustmentRepository;
    private final NumberGenerator numberGenerator;
    private final AuditService auditService;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public PageResponse<InventoryRowResponse> list(Long branchId, Long categoryId, String search,
                                                   boolean lowOnly, int page, int size) {
        Long storeId = TenantContext.storeId();
        AuthUser user = TenantContext.user();
        if (branchId != null) {
            TenantContext.requireBranch(branchId);
        }
        List<Branch> branches = branchRepository
                .findByStoreIdAndStatusOrderByBranchName(storeId, StoreStatus.ACTIVE).stream()
                .filter(branch -> user.canAccessBranch(branch.getId()))
                .filter(branch -> branchId == null || branch.getId().equals(branchId))
                .toList();
        List<InventoryRowResponse> rows = buildRows(storeId, branches, categoryId, search, lowOnly);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int total = rows.size();
        int from = Math.min(page * safeSize, total);
        int to = Math.min(from + safeSize, total);
        return new PageResponse<>(rows.subList(from, to), page, safeSize, total,
                safeSize == 0 ? 0 : (int) Math.ceil(total / (double) safeSize));
    }

    @Transactional(readOnly = true)
    public List<InventoryRowResponse> lowStock(Long branchId, int limit) {
        Long storeId = TenantContext.storeId();
        AuthUser user = TenantContext.user();
        if (branchId != null) {
            TenantContext.requireBranch(branchId);
        }
        List<Branch> branches = branchRepository
                .findByStoreIdAndStatusOrderByBranchName(storeId, StoreStatus.ACTIVE).stream()
                .filter(branch -> user.canAccessBranch(branch.getId()))
                .filter(branch -> branchId == null || branch.getId().equals(branchId))
                .toList();
        return buildRows(storeId, branches, null, null, true).stream().limit(limit).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryTransactionResponse> transactions(Long branchId, Long itemId,
                                                                   String search, int page, int size) {
        Long storeId = TenantContext.storeId();
        AuthUser user = TenantContext.user();
        if (branchId != null) {
            TenantContext.requireBranch(branchId);
        }
        Page<InventoryTransaction> result = transactionRepository.search(storeId, branchId,
                user.allBranches() ? null : user.branchIds(), itemId, clean(search),
                Pagination.of(page, size));
        Map<Long, Branch> branches = branchRepository.findByStoreIdOrderByBranchName(storeId).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));
        Map<Long, Item> items = itemRepository.findByStoreIdOrderByItemName(storeId).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        return PageResponse.of(result, txn -> {
            Item item = items.get(txn.getItemId());
            Branch branch = branches.get(txn.getBranchId());
            return new InventoryTransactionResponse(txn.getId(), txn.getBranchId(),
                    branch == null ? "" : branch.getBranchName(), txn.getItemId(),
                    item == null ? "" : item.getItemName(), txn.getTransactionType().name(),
                    txn.getReferenceType(), txn.getReferenceNumber(), txn.getQuantityIn(),
                    txn.getQuantityOut(), txn.getBalanceAfter(),
                    item == null ? "" : item.getBaseUnit(), txn.getNotes(),
                    txn.getTransactionDate());
        });
    }

    @Transactional
    public InventoryRowResponse adjust(StockAdjustmentRequest request) {
        Long storeId = TenantContext.storeId();
        TenantContext.requireBranch(request.branchId());
        Branch branch = activeBranch(request.branchId(), storeId);
        Item item = itemRepository.findByIdAndStoreId(request.itemId(), storeId)
                .orElseThrow(() -> ApiException.badRequest("Select a valid item"));
        String unit = UnitConverter.parse(request.unit());
        if (unit == null) {
            unit = item.getBaseUnit();
        }
        BigDecimal baseQuantity = UnitConverter.toBase(request.quantity(), unit, item.getBaseUnit());
        AdjustmentType type = "IN".equalsIgnoreCase(request.type())
                ? AdjustmentType.IN : AdjustmentType.OUT;
        String number = numberGenerator.nextAdjustment();
        BranchInventory inventory = lockOrCreate(storeId, branch.getId(), item);
        if (type == AdjustmentType.IN) {
            applyStockIn(inventory, item, baseQuantity, null, TransactionType.ADJUSTMENT_IN,
                    "ADJUSTMENT", null, number, request.reason());
        } else {
            applyStockOut(inventory, item, baseQuantity, TransactionType.ADJUSTMENT_OUT,
                    "ADJUSTMENT", null, number, request.reason());
        }
        adjustmentRepository.save(StockAdjustment.builder()
                .storeId(storeId)
                .branchId(branch.getId())
                .itemId(item.getId())
                .adjustmentNumber(number)
                .adjustmentType(type)
                .quantity(request.quantity())
                .baseQuantity(baseQuantity)
                .unit(unit)
                .reason(request.reason().trim())
                .createdBy(TenantContext.userId())
                .build());
        auditService.log("ADJUST", "INVENTORY", item.getId(),
                (type == AdjustmentType.IN ? "Stock added: " : "Stock removed: ")
                        + request.quantity() + " " + unit + " of " + item.getItemName()
                        + " at " + branch.getBranchName());
        return row(branch, item, inventory, null);
    }

    @Transactional
    public InventoryRowResponse updatePricing(Long branchId, Long itemId,
                                              BigDecimal minimumStock, BigDecimal purchasePrice,
                                              BigDecimal sellingPrice) {
        Long storeId = TenantContext.storeId();
        TenantContext.requireBranch(branchId);
        Branch branch = activeBranch(branchId, storeId);
        Item item = itemRepository.findByIdAndStoreId(itemId, storeId)
                .orElseThrow(() -> ApiException.notFound("Item not found"));
        BranchInventory inventory = lockOrCreate(storeId, branchId, item);
        inventory.setMinimumStock(minimumStock);
        inventory.setPurchasePrice(purchasePrice);
        inventory.setSellingPrice(sellingPrice);
        inventoryRepository.save(inventory);
        auditService.log("UPDATE_PRICING", "INVENTORY", itemId,
                "Updated branch pricing for " + item.getItemName());
        return row(branch, item, inventory, null);
    }

    public BranchInventory lockOrCreate(Long storeId, Long branchId, Item item) {
        entityManager.createNativeQuery("""
                        insert into branch_inventory
                            (store_id, branch_id, item_id, current_stock, minimum_stock,
                             purchase_price, selling_price, avg_cost, updated_at)
                        values (?, ?, ?, 0, ?, ?, ?, 0, now())
                        on conflict (store_id, branch_id, item_id) do nothing
                        """)
                .setParameter(1, storeId)
                .setParameter(2, branchId)
                .setParameter(3, item.getId())
                .setParameter(4, item.getMinStockLevel())
                .setParameter(5, item.getRate())
                .setParameter(6, item.getRate())
                .executeUpdate();
        return inventoryRepository.findForUpdate(storeId, branchId, item.getId())
                .orElseThrow(() -> ApiException.notFound("Inventory record could not be created"));
    }

    public void applyStockIn(BranchInventory inventory, Item item, BigDecimal quantity,
                             BigDecimal unitCost, TransactionType type, String referenceType,
                             Long referenceId, String referenceNumber, String notes) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("Quantity must be greater than zero");
        }
        BigDecimal oldStock = inventory.getCurrentStock();
        BigDecimal newStock = oldStock.add(quantity);
        if (unitCost != null && newStock.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal oldValue = oldStock.multiply(inventory.getAvgCost());
            BigDecimal incomingValue = quantity.multiply(unitCost);
            inventory.setAvgCost(oldValue.add(incomingValue)
                    .divide(newStock, 4, RoundingMode.HALF_UP));
            if (type == TransactionType.PURCHASE_IN) {
                inventory.setPurchasePrice(unitCost.setScale(2, RoundingMode.HALF_UP));
            }
        }
        inventory.setCurrentStock(newStock);
        inventoryRepository.save(inventory);
        record(inventory, item, type, referenceType, referenceId, referenceNumber,
                quantity, BigDecimal.ZERO, notes);
    }

    public void applyStockOut(BranchInventory inventory, Item item, BigDecimal quantity,
                              TransactionType type, String referenceType, Long referenceId,
                              String referenceNumber, String notes) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("Quantity must be greater than zero");
        }
        if (inventory.getCurrentStock().compareTo(quantity) < 0) {
            throw ApiException.badRequest("Insufficient stock for " + item.getItemName()
                    + ". Available: " + strip(inventory.getCurrentStock()) + " "
                    + item.getBaseUnit() + ", required: " + strip(quantity) + " "
                    + item.getBaseUnit());
        }
        BigDecimal newStock = inventory.getCurrentStock().subtract(quantity);
        inventory.setCurrentStock(newStock);
        inventoryRepository.save(inventory);
        record(inventory, item, type, referenceType, referenceId, referenceNumber,
                BigDecimal.ZERO, quantity, notes);
    }

    private void record(BranchInventory inventory, Item item, TransactionType type,
                        String referenceType, Long referenceId, String referenceNumber,
                        BigDecimal quantityIn, BigDecimal quantityOut, String notes) {
        transactionRepository.save(InventoryTransaction.builder()
                .storeId(inventory.getStoreId())
                .branchId(inventory.getBranchId())
                .itemId(item.getId())
                .transactionType(type)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .referenceNumber(referenceNumber)
                .quantityIn(quantityIn)
                .quantityOut(quantityOut)
                .balanceAfter(inventory.getCurrentStock())
                .notes(notes != null && notes.length() > 255 ? notes.substring(0, 255) : notes)
                .transactionDate(Instant.now())
                .createdBy(TenantContext.userId())
                .build());
    }

    private List<InventoryRowResponse> buildRows(Long storeId, List<Branch> branches,
                                                 Long categoryId, String search, boolean lowOnly) {
        if (branches.isEmpty()) {
            return List.of();
        }
        Map<Long, Category> categories = categoryRepository.findByStoreIdOrderByCategoryName(storeId)
                .stream().collect(Collectors.toMap(Category::getId, Function.identity()));
        String needle = search == null || search.isBlank() ? null : search.trim().toLowerCase();
        List<Item> items = itemRepository.findByStoreIdOrderByItemName(storeId).stream()
                .filter(item -> categoryId == null || item.getCategoryId().equals(categoryId))
                .filter(item -> needle == null || item.getItemName().toLowerCase().contains(needle)
                        || item.getItemCode().toLowerCase().contains(needle)
                        || item.getBarcode().toLowerCase().contains(needle))
                .toList();
        if (items.isEmpty()) {
            return List.of();
        }
        List<Long> branchIds = branches.stream().map(Branch::getId).toList();
        Map<String, BranchInventory> inventoryByKey = inventoryRepository
                .findByStoreIdAndBranchIdIn(storeId, branchIds).stream()
                .collect(Collectors.toMap(inv -> key(inv.getBranchId(), inv.getItemId()),
                        Function.identity()));
        List<InventoryRowResponse> rows = new ArrayList<>();
        for (Branch branch : branches) {
            for (Item item : items) {
                BranchInventory inventory = inventoryByKey.get(key(branch.getId(), item.getId()));
                InventoryRowResponse row = row(branch, item, inventory, categories.get(item.getCategoryId()));
                if (!lowOnly || row.lowStock()) {
                    rows.add(row);
                }
            }
        }
        rows.sort(Comparator.comparing(InventoryRowResponse::itemName)
                .thenComparing(InventoryRowResponse::branchName));
        return rows;
    }

    private InventoryRowResponse row(Branch branch, Item item, BranchInventory inventory, Category category) {
        BigDecimal stock = inventory == null ? BigDecimal.ZERO : inventory.getCurrentStock();
        BigDecimal minimum = inventory == null ? item.getMinStockLevel() : inventory.getMinimumStock();
        BigDecimal purchase = inventory == null ? BigDecimal.ZERO : inventory.getPurchasePrice();
        BigDecimal selling = inventory == null ? item.getRate() : inventory.getSellingPrice();
        BigDecimal avgCost = inventory == null ? BigDecimal.ZERO : inventory.getAvgCost();
        return new InventoryRowResponse(
                inventory == null ? null : inventory.getId(),
                branch.getId(), branch.getBranchCode(), branch.getBranchName(),
                item.getId(), item.getItemCode(), item.getItemName(), item.getCategoryId(),
                category == null ? "" : category.getCategoryName(), item.getBaseUnit(),
                stock, minimum, purchase, selling,
                stock.multiply(avgCost).setScale(2, RoundingMode.HALF_UP),
                stock.compareTo(minimum) <= 0);
    }

    private Branch activeBranch(Long branchId, Long storeId) {
        Branch branch = branchRepository.findByIdAndStoreId(branchId, storeId)
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
        if (branch.getStatus() != StoreStatus.ACTIVE) {
            throw ApiException.badRequest("This branch is inactive");
        }
        return branch;
    }

    private String key(Long branchId, Long itemId) {
        return branchId + ":" + itemId;
    }

    private String strip(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
