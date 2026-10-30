package com.itemmanagement.service;

import com.itemmanagement.dto.dashboard.DashboardResponse;
import com.itemmanagement.dto.inventory.InventoryRowResponse;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.BranchInventory;
import com.itemmanagement.entity.Item;
import com.itemmanagement.entity.Purchase;
import com.itemmanagement.entity.Sale;
import com.itemmanagement.entity.SalesDetail;
import com.itemmanagement.entity.Supplier;
import com.itemmanagement.entity.enums.PurchaseStatus;
import com.itemmanagement.entity.enums.SaleStatus;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.repository.BranchInventoryRepository;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.CategoryRepository;
import com.itemmanagement.repository.ItemRepository;
import com.itemmanagement.repository.PurchaseRepository;
import com.itemmanagement.repository.SaleRepository;
import com.itemmanagement.repository.SalesDetailRepository;
import com.itemmanagement.repository.SupplierRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final BranchRepository branchRepository;
    private final BranchInventoryRepository inventoryRepository;
    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseRepository purchaseRepository;
    private final SaleRepository saleRepository;
    private final SalesDetailRepository salesDetailRepository;
    private final InventoryService inventoryService;

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(Long branchId) {
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
        List<Long> branchIds = branches.stream().map(Branch::getId).toList();
        LocalDate today = LocalDate.now();

        List<Item> items = itemRepository.findByStoreIdOrderByItemName(storeId);
        Map<Long, Item> itemsById = items.stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        Map<Long, Branch> branchesById = branches.stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));
        List<BranchInventory> inventories = branchIds.isEmpty() ? List.of()
                : inventoryRepository.findByStoreIdAndBranchIdIn(storeId, branchIds);
        Map<String, BranchInventory> inventoryByKey = inventories.stream()
                .collect(Collectors.toMap(inv -> inv.getBranchId() + ":" + inv.getItemId(),
                        Function.identity()));

        List<Purchase> todayPurchases = purchaseRepository.findForPeriod(storeId,
                user.allBranches() ? null : user.branchIds(), branchId, PurchaseStatus.COMPLETED,
                today, today);
        List<Sale> todaySales = saleRepository.findForPeriod(storeId,
                user.allBranches() ? null : user.branchIds(), branchId, SaleStatus.COMPLETED,
                today, today);
        BigDecimal todayPurchaseAmount = todayPurchases.stream().map(Purchase::getGrandTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal todaySalesAmount = todaySales.stream().map(Sale::getGrandTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<InventoryRowResponse> lowStockAll = inventoryService.lowStock(branchId, 5000);
        long lowStockCount = lowStockAll.size();

        List<DashboardResponse.BranchSummary> branchSummaries = branches.stream().map(branch -> {
            BigDecimal sales = todaySales.stream()
                    .filter(sale -> sale.getBranchId().equals(branch.getId()))
                    .map(Sale::getGrandTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal purchases = todayPurchases.stream()
                    .filter(purchase -> purchase.getBranchId().equals(branch.getId()))
                    .map(Purchase::getGrandTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal stockValue = BigDecimal.ZERO;
            long low = 0;
            for (Item item : items) {
                BranchInventory inv = inventoryByKey.get(branch.getId() + ":" + item.getId());
                BigDecimal stock = inv == null ? BigDecimal.ZERO : inv.getCurrentStock();
                BigDecimal minimum = inv == null ? item.getMinStockLevel() : inv.getMinimumStock();
                BigDecimal avg = inv == null ? BigDecimal.ZERO : inv.getAvgCost();
                stockValue = stockValue.add(stock.multiply(avg));
                if (stock.compareTo(minimum) <= 0) {
                    low++;
                }
            }
            return new DashboardResponse.BranchSummary(branch.getId(), branch.getBranchName(),
                    money(sales), money(purchases), money(stockValue), low);
        }).toList();

        Map<Long, Supplier> suppliers = supplierRepository.findByStoreIdOrderBySupplierName(storeId)
                .stream().collect(Collectors.toMap(Supplier::getId, Function.identity()));
        List<DashboardResponse.RecentTransaction> recentPurchases = purchaseRepository
                .search(storeId, branchId, user.allBranches() ? null : user.branchIds(),
                        PurchaseStatus.COMPLETED, null, null, "", PageRequest.of(0, 5))
                .getContent().stream()
                .map(purchase -> new DashboardResponse.RecentTransaction(purchase.getId(),
                        purchase.getPurchaseNumber(),
                        supplierName(purchase.getSupplierId(), suppliers),
                        branchName(purchase.getBranchId(), branchesById),
                        purchase.getPurchaseDate(), purchase.getGrandTotal(),
                        purchase.getStatus().name()))
                .toList();
        List<DashboardResponse.RecentTransaction> recentSales = saleRepository
                .search(storeId, branchId, user.allBranches() ? null : user.branchIds(),
                        SaleStatus.COMPLETED, null, null, "", PageRequest.of(0, 5))
                .getContent().stream()
                .map(sale -> new DashboardResponse.RecentTransaction(sale.getId(),
                        sale.getSalesNumber(),
                        sale.getCustomerName() == null || sale.getCustomerName().isBlank()
                                ? "Walk-in customer" : sale.getCustomerName(),
                        branchName(sale.getBranchId(), branchesById), sale.getSalesDate(),
                        sale.getGrandTotal(), sale.getStatus().name()))
                .toList();

        LocalDate from = today.minusDays(29);
        List<SalesDetail> salesDetails = salesDetailRepository.findForPeriod(storeId,
                user.allBranches() ? null : user.branchIds(), branchId, SaleStatus.COMPLETED, from, today);
        Map<Long, BigDecimal[]> soldByItem = new HashMap<>();
        for (SalesDetail detail : salesDetails) {
            BigDecimal[] totals = soldByItem.computeIfAbsent(detail.getItemId(),
                    key -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            totals[0] = totals[0].add(detail.getBaseQuantity());
            totals[1] = totals[1].add(detail.getTotalAmount());
        }
        List<DashboardResponse.TopItem> topItems = soldByItem.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Long, BigDecimal[]> entry) -> entry.getValue()[0])
                        .reversed())
                .limit(5)
                .map(entry -> {
                    Item item = itemsById.get(entry.getKey());
                    return new DashboardResponse.TopItem(entry.getKey(),
                            item == null ? "Item" : item.getItemName(),
                            item == null ? "" : item.getBaseUnit().name(),
                            entry.getValue()[0], money(entry.getValue()[1]));
                })
                .toList();

        BigDecimal totalQuantity = inventories.stream().map(BranchInventory::getCurrentStock)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalValue = inventories.stream()
                .map(inv -> inv.getCurrentStock().multiply(inv.getAvgCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long itemsWithStock = inventories.stream()
                .filter(inv -> inv.getCurrentStock().compareTo(BigDecimal.ZERO) > 0)
                .map(BranchInventory::getItemId).distinct().count();
        long itemsWithoutStock = Math.max(items.size() - itemsWithStock, 0);

        DashboardResponse.Summary summary = new DashboardResponse.Summary(
                branches.size(), categoryRepository.countByStoreId(storeId),
                itemRepository.countByStoreId(storeId), supplierRepository.countByStoreId(storeId),
                money(todayPurchaseAmount), money(todaySalesAmount), lowStockCount,
                money(totalValue));
        return new DashboardResponse(summary, branchSummaries, recentPurchases, recentSales,
                lowStockAll.stream().limit(8).toList(), topItems,
                new DashboardResponse.StockSummary(quantity(totalQuantity), money(totalValue),
                        itemsWithStock, itemsWithoutStock));
    }

    private String supplierName(Long supplierId, Map<Long, Supplier> suppliers) {
        Supplier supplier = suppliers.get(supplierId);
        return supplier == null ? "" : supplier.getSupplierName();
    }

    private String branchName(Long branchId, Map<Long, Branch> branches) {
        Branch branch = branches.get(branchId);
        return branch == null ? "" : branch.getBranchName();
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal quantity(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }
}
