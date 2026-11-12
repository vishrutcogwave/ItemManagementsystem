package com.itemmanagement.service;

import com.itemmanagement.dto.inventory.InventoryRowResponse;
import com.itemmanagement.dto.report.BranchReportResponse;
import com.itemmanagement.dto.report.GstReportResponse;
import com.itemmanagement.dto.report.LowStockReportResponse;
import com.itemmanagement.dto.report.ProfitReportResponse;
import com.itemmanagement.dto.report.PurchaseReportResponse;
import com.itemmanagement.dto.report.SalesReportResponse;
import com.itemmanagement.dto.report.StockReportResponse;
import com.itemmanagement.dto.report.StoreReportResponse;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.BranchInventory;
import com.itemmanagement.entity.Category;
import com.itemmanagement.entity.InventoryTransaction;
import com.itemmanagement.entity.Item;
import com.itemmanagement.entity.Purchase;
import com.itemmanagement.entity.PurchaseDetail;
import com.itemmanagement.entity.Sale;
import com.itemmanagement.entity.SalesDetail;
import com.itemmanagement.entity.Supplier;
import com.itemmanagement.entity.enums.PurchaseStatus;
import com.itemmanagement.entity.enums.SaleStatus;
import com.itemmanagement.entity.enums.TransactionType;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchInventoryRepository;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.CategoryRepository;
import com.itemmanagement.repository.InventoryTransactionRepository;
import com.itemmanagement.repository.ItemRepository;
import com.itemmanagement.repository.PurchaseDetailRepository;
import com.itemmanagement.repository.PurchaseRepository;
import com.itemmanagement.repository.SaleRepository;
import com.itemmanagement.repository.SalesDetailRepository;
import com.itemmanagement.repository.SupplierRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final BranchRepository branchRepository;
    private final BranchInventoryRepository inventoryRepository;
    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseRepository purchaseRepository;
    private final PurchaseDetailRepository purchaseDetailRepository;
    private final SaleRepository saleRepository;
    private final SalesDetailRepository salesDetailRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final InventoryService inventoryService;

    @Transactional(readOnly = true)
    public PurchaseReportResponse purchaseReport(LocalDate from, LocalDate to, Long branchId,
                                                 Long categoryId, Long itemId, String search) {
        Scope scope = scope(branchId);
        if (scope.branchIds().isEmpty()) {
            return new PurchaseReportResponse(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        DateRange range = range(from, to);
        List<Purchase> purchases = purchaseRepository.findForPeriod(scope.storeId(), scope.branchIds(),
                branchId, PurchaseStatus.COMPLETED, range.from(), range.to());
        if (purchases.isEmpty()) {
            return new PurchaseReportResponse(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        Map<Long, Purchase> purchaseById = purchases.stream()
                .collect(Collectors.toMap(Purchase::getId, Function.identity()));
        Map<Long, Supplier> suppliers = supplierRepository
                .findByStoreIdOrderBySupplierName(scope.storeId()).stream()
                .collect(Collectors.toMap(Supplier::getId, Function.identity()));
        String needle = clean(search);
        List<PurchaseReportResponse.Row> rows = new ArrayList<>();
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        Set<Long> countedPurchases = new HashSet<>();
        List<PurchaseDetail> details = purchaseDetailRepository
                .findByPurchaseIdIn(purchases.stream().map(Purchase::getId).toList());
        for (PurchaseDetail detail : details) {
            Item item = scope.items().get(detail.getItemId());
            if (item == null || !matchesFilters(item, categoryId, itemId, needle)) {
                continue;
            }
            Purchase purchase = purchaseById.get(detail.getPurchaseId());
            if (purchase == null) {
                continue;
            }
            Supplier supplier = suppliers.get(purchase.getSupplierId());
            Branch branch = scope.branches().get(purchase.getBranchId());
            rows.add(new PurchaseReportResponse.Row(purchase.getPurchaseNumber(),
                    purchase.getPurchaseDate(),
                    supplier == null ? "" : supplier.getSupplierName(),
                    branch == null ? "" : branch.getBranchName(), item.getItemCode(),
                    item.getItemName(), detail.getQuantity(), detail.getUnit(),
                    detail.getPurchaseRate(), detail.getTotalAmount()));
            totalQuantity = totalQuantity.add(detail.getBaseQuantity());
            // The purchase is settled on one bill amount, not on summed line rates.
            if (countedPurchases.add(purchase.getId())) {
                totalAmount = totalAmount.add(money(purchase.getGrandTotal()));
            }
        }
        rows.sort(Comparator.comparing(PurchaseReportResponse.Row::purchaseDate).reversed()
                .thenComparing(PurchaseReportResponse.Row::purchaseNumber));
        return new PurchaseReportResponse(rows, quantity(totalQuantity), money(totalAmount), rows.size());
    }

    @Transactional(readOnly = true)
    public SalesReportResponse salesReport(LocalDate from, LocalDate to, Long branchId,
                                           Long categoryId, Long itemId, String search) {
        Scope scope = scope(branchId);
        if (scope.branchIds().isEmpty()) {
            return new SalesReportResponse(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        DateRange range = range(from, to);
        List<Sale> sales = saleRepository.findForPeriod(scope.storeId(), scope.branchIds(),
                branchId, SaleStatus.COMPLETED, range.from(), range.to());
        if (sales.isEmpty()) {
            return new SalesReportResponse(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        Map<Long, Sale> saleById = sales.stream()
                .collect(Collectors.toMap(Sale::getId, Function.identity()));
        String needle = clean(search);
        List<SalesReportResponse.Row> rows = new ArrayList<>();
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<SalesDetail> details = salesDetailRepository
                .findBySalesIdIn(sales.stream().map(Sale::getId).toList());
        for (SalesDetail detail : details) {
            Item item = scope.items().get(detail.getItemId());
            if (item == null || !matchesFilters(item, categoryId, itemId, needle)) {
                continue;
            }
            Sale sale = saleById.get(detail.getSalesId());
            if (sale == null) {
                continue;
            }
            Branch branch = scope.branches().get(sale.getBranchId());
            rows.add(new SalesReportResponse.Row(sale.getSalesNumber(), sale.getSalesDate(),
                    branch == null ? "" : branch.getBranchName(),
                    sale.getCustomerName() == null ? "Walk-in customer" : sale.getCustomerName(),
                    item.getItemCode(), item.getItemName(), detail.getQuantity(), detail.getUnit(),
                    detail.getSellingRate(), detail.getTotalAmount()));
            totalQuantity = totalQuantity.add(detail.getBaseQuantity());
            totalAmount = totalAmount.add(detail.getTotalAmount());
        }
        rows.sort(Comparator.comparing(SalesReportResponse.Row::salesDate).reversed()
                .thenComparing(SalesReportResponse.Row::salesNumber));
        return new SalesReportResponse(rows, quantity(totalQuantity), money(totalAmount), rows.size());
    }

    @Transactional(readOnly = true)
    public GstReportResponse gstReport(LocalDate from, LocalDate to, Long branchId, BigDecimal taxRate) {
        GstReportResponse.Totals empty = new GstReportResponse.Totals(
                money(BigDecimal.ZERO), money(BigDecimal.ZERO), money(BigDecimal.ZERO),
                money(BigDecimal.ZERO), money(BigDecimal.ZERO), 0);
        Scope scope = scope(branchId);
        if (scope.branchIds().isEmpty()) {
            return new GstReportResponse(List.of(), empty);
        }
        DateRange range = range(from, to);
        List<Sale> sales = saleRepository.findForPeriod(scope.storeId(), scope.branchIds(), branchId,
                SaleStatus.COMPLETED, range.from(), range.to());
        if (sales.isEmpty()) {
            return new GstReportResponse(List.of(), empty);
        }
        Map<Long, Sale> saleById = sales.stream()
                .collect(Collectors.toMap(Sale::getId, Function.identity()));
        List<SalesDetail> details = salesDetailRepository
                .findBySalesIdIn(sales.stream().map(Sale::getId).toList());
        Map<String, GstAggregate> groups = new LinkedHashMap<>();
        java.util.Set<Long> invoicedSales = new java.util.HashSet<>();
        for (SalesDetail detail : details) {
            Sale sale = saleById.get(detail.getSalesId());
            if (sale == null) {
                continue;
            }
            BigDecimal gstPercentage = detail.getGstPercentage() == null
                    ? BigDecimal.ZERO : detail.getGstPercentage();
            if (taxRate != null && gstPercentage.compareTo(taxRate) != 0) {
                continue;
            }
            Branch branch = scope.branches().get(sale.getBranchId());
            String branchName = branch == null ? "" : branch.getBranchName();
            String key = sale.getBranchId() + "|" + detail.getTaxNameSnapshot() + "|"
                    + gstPercentage.stripTrailingZeros().toPlainString();
            GstAggregate aggregate = groups.computeIfAbsent(key, k -> new GstAggregate(
                    sale.getBranchId(), branchName, detail.getTaxNameSnapshot(), gstPercentage,
                    detail.getCgstPercentage(), detail.getSgstPercentage(), detail.getIgstPercentage()));
            aggregate.taxable = aggregate.taxable.add(detail.getTotalAmount().subtract(detail.getTax()));
            aggregate.cgst = aggregate.cgst.add(amount(detail.getCgstAmount()));
            aggregate.sgst = aggregate.sgst.add(amount(detail.getSgstAmount()));
            aggregate.igst = aggregate.igst.add(amount(detail.getIgstAmount()));
            aggregate.tax = aggregate.tax.add(amount(detail.getTaxAmount()));
            aggregate.saleIds.add(detail.getSalesId());
            invoicedSales.add(detail.getSalesId());
        }
        List<GstReportResponse.Row> rows = groups.values().stream()
                .sorted(Comparator.comparing((GstAggregate a) -> a.branchName)
                        .thenComparing(a -> a.gstPercentage))
                .map(a -> new GstReportResponse.Row(a.branchId, a.branchName, a.taxName,
                        a.gstPercentage, a.cgstPercentage, a.sgstPercentage, a.igstPercentage,
                        money(a.taxable), money(a.cgst), money(a.sgst), money(a.igst),
                        money(a.tax), a.saleIds.size()))
                .toList();
        BigDecimal totalTaxable = groups.values().stream().map(a -> a.taxable)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCgst = groups.values().stream().map(a -> a.cgst)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSgst = groups.values().stream().map(a -> a.sgst)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalIgst = groups.values().stream().map(a -> a.igst)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalTax = groups.values().stream().map(a -> a.tax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new GstReportResponse(rows, new GstReportResponse.Totals(
                money(totalTaxable), money(totalCgst), money(totalSgst), money(totalIgst),
                money(totalTax), invoicedSales.size()));
    }

    @Transactional(readOnly = true)
    public StockReportResponse stockReport(LocalDate from, LocalDate to, Long branchId,
                                           Long categoryId, Long itemId, String search) {
        Scope scope = scope(branchId);
        if (scope.branchIds().isEmpty()) {
            return new StockReportResponse(List.of(), BigDecimal.ZERO, BigDecimal.ZERO);
        }
        DateRange range = range(from, to);
        ZoneId zone = ZoneId.systemDefault();
        Instant fromInstant = range.from().atStartOfDay(zone).toInstant();
        Instant toInstant = range.to().plusDays(1).atStartOfDay(zone).toInstant();
        List<InventoryTransaction> transactions = transactionRepository
                .findByStoreIdAndTransactionDateGreaterThanEqualAndTransactionDateLessThan(
                        scope.storeId(), fromInstant, toInstant).stream()
                .filter(txn -> scope.branches().containsKey(txn.getBranchId()))
                .toList();
        Map<String, Movement> movements = new HashMap<>();
        for (InventoryTransaction txn : transactions) {
            Movement movement = movements.computeIfAbsent(key(txn.getBranchId(), txn.getItemId()),
                    k -> new Movement());
            switch (txn.getTransactionType()) {
                case PURCHASE_IN -> movement.purchased = movement.purchased.add(txn.getQuantityIn());
                case PURCHASE_REVERSAL -> movement.purchased = movement.purchased.subtract(txn.getQuantityOut());
                case SALE_OUT -> movement.sold = movement.sold.add(txn.getQuantityOut());
                case SALE_REVERSAL -> movement.sold = movement.sold.subtract(txn.getQuantityIn());
                case TRANSFER_IN -> movement.transferIn = movement.transferIn.add(txn.getQuantityIn());
                case TRANSFER_OUT -> movement.transferOut = movement.transferOut.add(txn.getQuantityOut());
                case ADJUSTMENT_IN -> movement.adjustmentIn = movement.adjustmentIn.add(txn.getQuantityIn());
                case ADJUSTMENT_OUT -> movement.adjustmentOut = movement.adjustmentOut.add(txn.getQuantityOut());
                case OPENING_STOCK -> movement.purchased = movement.purchased.add(txn.getQuantityIn());
            }
        }
        List<BranchInventory> inventories = inventoryRepository
                .findByStoreIdAndBranchIdIn(scope.storeId(), scope.branchIds());
        Map<Long, Category> categories = scope.categories();
        String needle = clean(search);

        Map<String, StockReportResponse.Row> rowsByKey = new LinkedHashMap<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        for (BranchInventory inventory : inventories) {
            Item item = scope.items().get(inventory.getItemId());
            if (item == null || !matchesFilters(item, categoryId, itemId, needle)) {
                continue;
            }
            Branch branch = scope.branches().get(inventory.getBranchId());
            Movement movement = movements.getOrDefault(key(inventory.getBranchId(), item.getId()),
                    new Movement());
            rowsByKey.put(key(inventory.getBranchId(), item.getId()), stockRow(item, branch,
                    categories.get(item.getCategoryId()), movement, inventory.getCurrentStock()));
            totalValue = totalValue.add(inventory.getCurrentStock().multiply(inventory.getAvgCost()));
        }
        for (Map.Entry<String, Movement> entry : movements.entrySet()) {
            if (rowsByKey.containsKey(entry.getKey())) {
                continue;
            }
            String[] parts = entry.getKey().split(":");
            Long currentBranchId = Long.valueOf(parts[0]);
            Long currentItemId = Long.valueOf(parts[1]);
            Item item = scope.items().get(currentItemId);
            if (item == null || !matchesFilters(item, categoryId, itemId, needle)) {
                continue;
            }
            Branch branch = scope.branches().get(currentBranchId);
            rowsByKey.put(entry.getKey(), stockRow(item, branch, categories.get(item.getCategoryId()),
                    entry.getValue(), BigDecimal.ZERO));
        }
        List<StockReportResponse.Row> rows = new ArrayList<>(rowsByKey.values());
        rows.sort(Comparator.comparing(StockReportResponse.Row::branchName)
                .thenComparing(StockReportResponse.Row::itemName));
        BigDecimal totalStock = rows.stream().map(StockReportResponse.Row::currentStock)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new StockReportResponse(rows, quantity(totalStock), money(totalValue));
    }

    @Transactional(readOnly = true)
    public LowStockReportResponse lowStockReport(Long branchId, Long categoryId, String search) {
        List<InventoryRowResponse> rows = inventoryService.lowStock(branchId, 5000);
        String needle = clean(search);
        List<InventoryRowResponse> filtered = rows.stream()
                .filter(row -> categoryId == null || categoryId.equals(row.categoryId()))
                .filter(row -> needle == null || row.itemName().toLowerCase().contains(needle)
                        || row.itemCode().toLowerCase().contains(needle))
                .toList();
        return new LowStockReportResponse(filtered, filtered.size());
    }

    @Transactional(readOnly = true)
    public BranchReportResponse branchReport(LocalDate from, LocalDate to, Long branchId) {
        StoreReportResponse store = storeReport(from, to, branchId);
        List<BranchReportResponse.Row> rows = store.branches();
        BranchReportResponse.Totals totals = new BranchReportResponse.Totals(
                store.totalPurchaseAmount(), store.totalSalesAmount(), store.totalItemsSold(),
                store.totalInventoryValue(), store.totalLowStockItems());
        return new BranchReportResponse(rows, totals);
    }

    @Transactional(readOnly = true)
    public StoreReportResponse storeReport(LocalDate from, LocalDate to, Long branchId) {
        Scope scope = scope(branchId);
        if (scope.branchIds().isEmpty()) {
            return new StoreReportResponse(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of());
        }
        DateRange range = range(from, to);
        List<Purchase> purchases = purchaseRepository.findForPeriod(scope.storeId(), scope.branchIds(),
                branchId, PurchaseStatus.COMPLETED, range.from(), range.to());
        List<Sale> sales = saleRepository.findForPeriod(scope.storeId(), scope.branchIds(),
                branchId, SaleStatus.COMPLETED, range.from(), range.to());
        List<SalesDetail> salesDetails = salesDetailRepository.findForPeriod(scope.storeId(),
                scope.branchIds(), branchId, SaleStatus.COMPLETED, range.from(), range.to());
        List<BranchInventory> inventories = inventoryRepository
                .findByStoreIdAndBranchIdIn(scope.storeId(), scope.branchIds());
        Map<String, BranchInventory> inventoryByKey = inventories.stream()
                .collect(Collectors.toMap(inv -> key(inv.getBranchId(), inv.getItemId()),
                        Function.identity()));
        Map<Long, BigDecimal> purchaseByBranch = new HashMap<>();
        for (Purchase purchase : purchases) {
            purchaseByBranch.merge(purchase.getBranchId(), purchase.getGrandTotal(), BigDecimal::add);
        }
        Map<Long, BigDecimal> salesByBranch = new HashMap<>();
        for (Sale sale : sales) {
            salesByBranch.merge(sale.getBranchId(), sale.getGrandTotal(), BigDecimal::add);
        }
        Map<Long, Sale> saleById = sales.stream()
                .collect(Collectors.toMap(Sale::getId, Function.identity()));
        Map<Long, BigDecimal> quantityByBranch = new HashMap<>();
        for (SalesDetail detail : salesDetails) {
            Sale sale = saleById.get(detail.getSalesId());
            if (sale != null) {
                quantityByBranch.merge(sale.getBranchId(), detail.getBaseQuantity(), BigDecimal::add);
            }
        }
        List<Item> items = new ArrayList<>(scope.items().values());
        List<BranchReportResponse.Row> rows = new ArrayList<>();
        BigDecimal totalInventoryQuantity = BigDecimal.ZERO;
        BigDecimal totalInventoryValue = BigDecimal.ZERO;
        long totalLowStock = 0;
        for (Branch branch : scope.branches().values()) {
            BigDecimal stockValue = BigDecimal.ZERO;
            long lowStock = 0;
            for (Item item : items) {
                BranchInventory inventory = inventoryByKey.get(key(branch.getId(), item.getId()));
                BigDecimal stock = inventory == null ? BigDecimal.ZERO : inventory.getCurrentStock();
                BigDecimal minimum = inventory == null ? item.getMinStockLevel() : inventory.getMinimumStock();
                BigDecimal avg = inventory == null ? BigDecimal.ZERO : inventory.getAvgCost();
                stockValue = stockValue.add(stock.multiply(avg));
                totalInventoryQuantity = totalInventoryQuantity.add(stock);
                if (stock.compareTo(minimum) <= 0) {
                    lowStock++;
                }
            }
            totalInventoryValue = totalInventoryValue.add(stockValue);
            totalLowStock += lowStock;
            rows.add(new BranchReportResponse.Row(branch.getId(), branch.getBranchName(),
                    money(purchaseByBranch.getOrDefault(branch.getId(), BigDecimal.ZERO)),
                    money(salesByBranch.getOrDefault(branch.getId(), BigDecimal.ZERO)),
                    quantity(quantityByBranch.getOrDefault(branch.getId(), BigDecimal.ZERO)),
                    money(stockValue), lowStock));
        }
        rows.sort(Comparator.comparing(BranchReportResponse.Row::branchName));
        BigDecimal totalPurchases = purchases.stream().map(Purchase::getGrandTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSales = sales.stream().map(Sale::getGrandTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalItemsSold = salesDetails.stream().map(SalesDetail::getBaseQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new StoreReportResponse(money(totalPurchases), money(totalSales),
                quantity(totalItemsSold), quantity(totalInventoryQuantity),
                money(totalInventoryValue), totalLowStock, rows);
    }

    @Transactional(readOnly = true)
    public ProfitReportResponse profitReport(LocalDate from, LocalDate to, Long branchId,
                                             Long categoryId, Long itemId, String search) {
        Scope scope = scope(branchId);
        if (scope.branchIds().isEmpty()) {
            return new ProfitReportResponse(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    List.of(), List.of());
        }
        DateRange range = range(from, to);
        List<SalesDetail> details = salesDetailRepository.findForPeriod(scope.storeId(),
                scope.branchIds(), branchId, SaleStatus.COMPLETED, range.from(), range.to());
        Map<Long, SalesDetailAggregate> byItemId = new LinkedHashMap<>();
        Map<Long, ProfitAggregate> byBranchId = new LinkedHashMap<>();
        List<Sale> sales = saleRepository.findForPeriod(scope.storeId(), scope.branchIds(),
                branchId, SaleStatus.COMPLETED, range.from(), range.to());
        Map<Long, Sale> saleById = sales.stream()
                .collect(Collectors.toMap(Sale::getId, Function.identity()));
        String needle = clean(search);
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalCogs = BigDecimal.ZERO;
        for (SalesDetail detail : details) {
            Item item = scope.items().get(detail.getItemId());
            if (item == null || !matchesFilters(item, categoryId, itemId, needle)) {
                continue;
            }
            Sale sale = saleById.get(detail.getSalesId());
            if (sale == null) {
                continue;
            }
            BigDecimal cogs = detail.getBaseQuantity().multiply(detail.getCostRate());
            BigDecimal lineRevenue = detail.getTotalAmount().subtract(detail.getTax());
            totalRevenue = totalRevenue.add(lineRevenue);
            totalCogs = totalCogs.add(cogs);
            SalesDetailAggregate itemAggregate = byItemId.computeIfAbsent(item.getId(),
                    key -> new SalesDetailAggregate());
            itemAggregate.quantity = itemAggregate.quantity.add(detail.getBaseQuantity());
            itemAggregate.revenue = itemAggregate.revenue.add(lineRevenue);
            itemAggregate.cogs = itemAggregate.cogs.add(cogs);
            ProfitAggregate branchAggregate = byBranchId.computeIfAbsent(sale.getBranchId(),
                    key -> new ProfitAggregate());
            branchAggregate.revenue = branchAggregate.revenue.add(lineRevenue);
            branchAggregate.cogs = branchAggregate.cogs.add(cogs);
        }
        List<ProfitReportResponse.ItemProfit> byItem = byItemId.entrySet().stream()
                .map(entry -> {
                    Item item = scope.items().get(entry.getKey());
                    SalesDetailAggregate aggregate = entry.getValue();
                    return new ProfitReportResponse.ItemProfit(entry.getKey(),
                            item == null ? "Item" : item.getItemName(),
                            quantity(aggregate.quantity), money(aggregate.revenue),
                            money(aggregate.cogs), money(aggregate.revenue.subtract(aggregate.cogs)));
                })
                .sorted(Comparator.comparing(ProfitReportResponse.ItemProfit::profit).reversed())
                .toList();
        List<ProfitReportResponse.BranchProfit> byBranch = byBranchId.entrySet().stream()
                .map(entry -> {
                    Branch branch = scope.branches().get(entry.getKey());
                    ProfitAggregate aggregate = entry.getValue();
                    return new ProfitReportResponse.BranchProfit(entry.getKey(),
                            branch == null ? "" : branch.getBranchName(),
                            money(aggregate.revenue), money(aggregate.cogs),
                            money(aggregate.revenue.subtract(aggregate.cogs)));
                })
                .sorted(Comparator.comparing(ProfitReportResponse.BranchProfit::profit).reversed())
                .toList();
        return new ProfitReportResponse(money(totalRevenue), money(totalCogs),
                money(totalRevenue.subtract(totalCogs)), byItem, byBranch);
    }

    private StockReportResponse.Row stockRow(Item item, Branch branch, Category category,
                                             Movement movement, BigDecimal currentStock) {
        BigDecimal net = movement.purchased
                .subtract(movement.sold)
                .add(movement.transferIn)
                .subtract(movement.transferOut)
                .add(movement.adjustmentIn)
                .subtract(movement.adjustmentOut);
        return new StockReportResponse.Row(item.getItemCode(), item.getItemName(),
                category == null ? "" : category.getCategoryName(),
                branch == null ? "" : branch.getBranchName(),
                quantity(currentStock.subtract(net)), quantity(movement.purchased),
                quantity(movement.sold), quantity(movement.transferIn), quantity(movement.transferOut),
                quantity(movement.adjustmentIn), quantity(movement.adjustmentOut),
                quantity(currentStock), item.getBaseUnit());
    }

    private Scope scope(Long branchId) {
        Long storeId = TenantContext.storeId();
        AuthUser user = TenantContext.user();
        if (branchId != null) {
            TenantContext.requireBranch(branchId);
        }
        List<Branch> branches = branchRepository.findByStoreIdOrderByBranchName(storeId).stream()
                .filter(branch -> user.canAccessBranch(branch.getId()))
                .filter(branch -> branchId == null || branch.getId().equals(branchId))
                .toList();
        Map<Long, Branch> branchMap = branches.stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Map<Long, Item> items = itemRepository.findByStoreIdOrderByItemName(storeId).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        Map<Long, Category> categories = categoryRepository.findByStoreIdOrderByCategoryName(storeId)
                .stream().collect(Collectors.toMap(Category::getId, Function.identity()));
        return new Scope(storeId, branches.stream().map(Branch::getId).toList(), branchMap, items, categories);
    }

    private DateRange range(LocalDate from, LocalDate to) {
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (start.isAfter(end)) {
            throw ApiException.badRequest("From date cannot be after to date");
        }
        return new DateRange(start, end);
    }

    private boolean matchesFilters(Item item, Long categoryId, Long itemId, String needle) {
        if (categoryId != null && !categoryId.equals(item.getCategoryId())) {
            return false;
        }
        if (itemId != null && !itemId.equals(item.getId())) {
            return false;
        }
        return needle == null || item.getItemName().toLowerCase().contains(needle)
                || item.getItemCode().toLowerCase().contains(needle);
    }

    private String key(Long branchId, Long itemId) {
        return branchId + ":" + itemId;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal quantity(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase();
    }

    private record Scope(Long storeId, List<Long> branchIds, Map<Long, Branch> branches,
                         Map<Long, Item> items, Map<Long, Category> categories) {
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }

    private static class Movement {
        BigDecimal purchased = BigDecimal.ZERO;
        BigDecimal sold = BigDecimal.ZERO;
        BigDecimal transferIn = BigDecimal.ZERO;
        BigDecimal transferOut = BigDecimal.ZERO;
        BigDecimal adjustmentIn = BigDecimal.ZERO;
        BigDecimal adjustmentOut = BigDecimal.ZERO;
    }

    private static class SalesDetailAggregate {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
    }

    private static class ProfitAggregate {
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
    }

    private static class GstAggregate {
        final Long branchId;
        final String branchName;
        final String taxName;
        final BigDecimal gstPercentage;
        final BigDecimal cgstPercentage;
        final BigDecimal sgstPercentage;
        final BigDecimal igstPercentage;
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        final java.util.Set<Long> saleIds = new java.util.HashSet<>();

        GstAggregate(Long branchId, String branchName, String taxName, BigDecimal gstPercentage,
                     BigDecimal cgstPercentage, BigDecimal sgstPercentage, BigDecimal igstPercentage) {
            this.branchId = branchId;
            this.branchName = branchName;
            this.taxName = taxName;
            this.gstPercentage = gstPercentage == null ? BigDecimal.ZERO : gstPercentage;
            this.cgstPercentage = cgstPercentage == null ? BigDecimal.ZERO : cgstPercentage;
            this.sgstPercentage = sgstPercentage == null ? BigDecimal.ZERO : sgstPercentage;
            this.igstPercentage = igstPercentage == null ? BigDecimal.ZERO : igstPercentage;
        }
    }
}
