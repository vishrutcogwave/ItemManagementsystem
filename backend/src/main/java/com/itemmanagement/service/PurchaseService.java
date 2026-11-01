package com.itemmanagement.service;

import com.itemmanagement.dto.transaction.PurchaseRequest;
import com.itemmanagement.dto.transaction.PurchaseResponse;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.BranchInventory;
import com.itemmanagement.entity.Item;
import com.itemmanagement.entity.Purchase;
import com.itemmanagement.entity.PurchaseDetail;
import com.itemmanagement.entity.Store;
import com.itemmanagement.entity.Supplier;
import com.itemmanagement.entity.TaxMaster;
import com.itemmanagement.entity.enums.PurchaseStatus;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.entity.enums.TransactionType;
import com.itemmanagement.entity.enums.Unit;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.ItemRepository;
import com.itemmanagement.repository.PurchaseDetailRepository;
import com.itemmanagement.repository.PurchaseRepository;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.repository.SupplierRepository;
import com.itemmanagement.repository.TaxMasterRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.GstCalculator;
import com.itemmanagement.util.PageResponse;
import com.itemmanagement.util.Pagination;
import com.itemmanagement.util.UnitConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final PurchaseRepository purchaseRepository;
    private final PurchaseDetailRepository detailRepository;
    private final SupplierRepository supplierRepository;
    private final BranchRepository branchRepository;
    private final ItemRepository itemRepository;
    private final StoreRepository storeRepository;
    private final TaxMasterRepository taxMasterRepository;
    private final InventoryService inventoryService;
    private final NumberGenerator numberGenerator;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<PurchaseResponse> list(Long branchId, PurchaseStatus status, LocalDate from,
                                               LocalDate to, String search, int page, int size) {
        Long storeId = TenantContext.storeId();
        AuthUser user = TenantContext.user();
        if (branchId != null) {
            TenantContext.requireBranch(branchId);
        }
        Page<Purchase> purchases = purchaseRepository.search(storeId, branchId,
                user.allBranches() ? null : user.branchIds(), status, from, to, clean(search),
                Pagination.of(page, size));
        Map<Long, Branch> branches = branches(storeId);
        Map<Long, Supplier> suppliers = suppliers(storeId);
        return PageResponse.of(purchases, purchase ->
                toResponse(purchase, List.of(), branches, suppliers, Map.of()));
    }

    @Transactional(readOnly = true)
    public PurchaseResponse get(Long id) {
        Long storeId = TenantContext.storeId();
        Purchase purchase = find(id, storeId);
        TenantContext.requireBranch(purchase.getBranchId());
        List<PurchaseDetail> details = detailRepository.findByPurchaseIdOrderByItemId(id);
        return toResponse(purchase, details, branches(storeId), suppliers(storeId), items(storeId));
    }

    @Transactional
    public PurchaseResponse create(PurchaseRequest request) {
        Long storeId = TenantContext.storeId();
        TenantContext.requireBranch(request.branchId());
        Branch branch = activeBranch(request.branchId(), storeId);
        Supplier supplier = supplier(request.supplierId(), storeId);
        Computed computed = compute(request, storeId, branch, supplier);
        PurchaseStatus status = request.status() == null ? PurchaseStatus.COMPLETED : request.status();
        if (status == PurchaseStatus.CANCELLED) {
            throw ApiException.badRequest("A purchase cannot be created as cancelled");
        }
        BigDecimal paidAmount = paid(request.paidAmount(), status, computed.grandTotal());
        Purchase purchase = purchaseRepository.save(Purchase.builder()
                .storeId(storeId)
                .branchId(branch.getId())
                .supplierId(supplier.getId())
                .purchaseNumber(numberGenerator.nextPurchase())
                .invoiceNumber(blankToNull(request.invoiceNumber()))
                .purchaseDate(request.purchaseDate())
                .subtotal(computed.subtotal())
                .discount(computed.discount())
                .tax(computed.tax())
                .taxableAmount(computed.taxable())
                .cgstAmount(computed.cgst())
                .sgstAmount(computed.sgst())
                .igstAmount(computed.igst())
                .grandTotal(computed.grandTotal())
                .paidAmount(paidAmount)
                .balanceAmount(computed.grandTotal().subtract(paidAmount))
                .notes(blankToNull(request.notes()))
                .status(status)
                .createdBy(TenantContext.userId())
                .build());
        saveDetails(purchase, computed.details());
        if (status == PurchaseStatus.COMPLETED) {
            applyStock(purchase, computed.details(), computed.items(), storeId, branch);
        }
        auditService.log("CREATE", "PURCHASE", purchase.getId(),
                "Purchase " + purchase.getPurchaseNumber() + " for " + branch.getBranchName()
                        + " (" + purchase.getStatus() + ")");
        return toResponse(purchase, computed.details(), Map.of(branch.getId(), branch),
                Map.of(supplier.getId(), supplier), computed.items());
    }

    @Transactional
    public PurchaseResponse updateDraft(Long id, PurchaseRequest request) {
        Long storeId = TenantContext.storeId();
        Purchase purchase = find(id, storeId);
        if (purchase.getStatus() != PurchaseStatus.DRAFT) {
            throw ApiException.badRequest("Only draft purchases can be edited");
        }
        TenantContext.requireBranch(request.branchId());
        Branch branch = activeBranch(request.branchId(), storeId);
        Supplier supplier = supplier(request.supplierId(), storeId);
        Computed computed = compute(request, storeId, branch, supplier);
        BigDecimal paidAmount = paid(request.paidAmount(), PurchaseStatus.DRAFT, computed.grandTotal());
        purchase.setBranchId(branch.getId());
        purchase.setSupplierId(supplier.getId());
        purchase.setInvoiceNumber(blankToNull(request.invoiceNumber()));
        purchase.setPurchaseDate(request.purchaseDate());
        purchase.setSubtotal(computed.subtotal());
        purchase.setDiscount(computed.discount());
        purchase.setTax(computed.tax());
        purchase.setTaxableAmount(computed.taxable());
        purchase.setCgstAmount(computed.cgst());
        purchase.setSgstAmount(computed.sgst());
        purchase.setIgstAmount(computed.igst());
        purchase.setGrandTotal(computed.grandTotal());
        purchase.setPaidAmount(paidAmount);
        purchase.setBalanceAmount(computed.grandTotal().subtract(paidAmount));
        purchase.setNotes(blankToNull(request.notes()));
        purchaseRepository.save(purchase);
        detailRepository.deleteByPurchaseId(purchase.getId());
        detailRepository.flush();
        saveDetails(purchase, computed.details());
        auditService.log("UPDATE", "PURCHASE", id, "Updated draft " + purchase.getPurchaseNumber());
        return toResponse(purchase, computed.details(), Map.of(branch.getId(), branch),
                Map.of(supplier.getId(), supplier), computed.items());
    }

    @Transactional
    public PurchaseResponse complete(Long id) {
        Long storeId = TenantContext.storeId();
        Purchase purchase = find(id, storeId);
        if (purchase.getStatus() != PurchaseStatus.DRAFT) {
            throw ApiException.badRequest("Only draft purchases can be completed");
        }
        TenantContext.requireBranch(purchase.getBranchId());
        Branch branch = activeBranch(purchase.getBranchId(), storeId);
        List<PurchaseDetail> details = detailRepository.findByPurchaseIdOrderByItemId(id);
        if (details.isEmpty()) {
            throw ApiException.badRequest("Add at least one item before completing this purchase");
        }
        Map<Long, Item> items = items(storeId);
        applyStock(purchase, details, items, storeId, branch);
        purchase.setStatus(PurchaseStatus.COMPLETED);
        purchaseRepository.save(purchase);
        auditService.log("COMPLETE", "PURCHASE", id, "Completed purchase " + purchase.getPurchaseNumber());
        return toResponse(purchase, details, branches(storeId), suppliers(storeId), items);
    }

    @Transactional
    public PurchaseResponse cancel(Long id) {
        Long storeId = TenantContext.storeId();
        Purchase purchase = find(id, storeId);
        if (purchase.getStatus() == PurchaseStatus.CANCELLED) {
            throw ApiException.badRequest("This purchase is already cancelled");
        }
        TenantContext.requireBranch(purchase.getBranchId());
        if (purchase.getStatus() == PurchaseStatus.COMPLETED) {
            List<PurchaseDetail> details = detailRepository.findByPurchaseIdOrderByItemId(id);
            Map<Long, Item> items = items(storeId);
            for (PurchaseDetail detail : details) {
                Item item = items.get(detail.getItemId());
                if (item == null) {
                    continue;
                }
                BranchInventory inventory =
                        inventoryService.lockOrCreate(storeId, purchase.getBranchId(), item);
                inventoryService.applyStockOut(inventory, item, detail.getBaseQuantity(),
                        TransactionType.PURCHASE_REVERSAL, "PURCHASE", purchase.getId(),
                        purchase.getPurchaseNumber(),
                        "Cancellation of purchase " + purchase.getPurchaseNumber());
            }
        }
        purchase.setStatus(PurchaseStatus.CANCELLED);
        purchaseRepository.save(purchase);
        auditService.log("CANCEL", "PURCHASE", id, "Cancelled purchase " + purchase.getPurchaseNumber());
        return toResponse(purchase, detailRepository.findByPurchaseIdOrderByItemId(id),
                branches(storeId), suppliers(storeId), items(storeId));
    }

    private void applyStock(Purchase purchase, List<PurchaseDetail> details, Map<Long, Item> items,
                            Long storeId, Branch branch) {
        for (PurchaseDetail detail : details) {
            Item item = items.get(detail.getItemId());
            if (item == null) {
                throw ApiException.badRequest("Item in this purchase is no longer available");
            }
            BranchInventory inventory =
                    inventoryService.lockOrCreate(storeId, branch.getId(), item);
            BigDecimal unitCost = unitCost(detail);
            inventoryService.applyStockIn(inventory, item, detail.getBaseQuantity(), unitCost,
                    TransactionType.PURCHASE_IN, "PURCHASE", purchase.getId(),
                    purchase.getPurchaseNumber(), "Purchase from supplier");
        }
    }

    private BigDecimal unitCost(PurchaseDetail detail) {
        if (detail.getBaseQuantity().compareTo(BigDecimal.ZERO) == 0) {
            return detail.getPurchaseRate();
        }
        BigDecimal taxable = detail.getTaxableAmount();
        if (taxable != null && taxable.compareTo(BigDecimal.ZERO) > 0) {
            return taxable.divide(detail.getBaseQuantity(), 4, RoundingMode.HALF_UP);
        }
        return detail.getTotalAmount().divide(detail.getBaseQuantity(), 4, RoundingMode.HALF_UP);
    }

    private Computed compute(PurchaseRequest request, Long storeId, Branch branch, Supplier supplier) {
        Map<Long, Item> items = items(storeId);
        Map<Long, TaxMaster> taxes = taxes(storeId);
        Store store = store(storeId);
        boolean inclusive = store.isTaxInclusive();
        boolean interState = GstCalculator.isInterState(branch.getState(), supplier.getState());
        Set<Long> seen = new HashSet<>();
        List<RawLine> rawLines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (PurchaseRequest.Line line : request.items()) {
            if (!seen.add(line.itemId())) {
                throw ApiException.badRequest("The same item cannot be added twice");
            }
            Item item = items.get(line.itemId());
            if (item == null) {
                throw ApiException.badRequest("Select a valid item");
            }
            if (item.getStatus() != StoreStatus.ACTIVE) {
                throw ApiException.badRequest(item.getItemName() + " is inactive");
            }
            Unit unit = UnitConverter.parse(line.unit());
            if (unit == null) {
                unit = item.getBaseUnit();
            }
            BigDecimal baseQuantity = UnitConverter.toBase(line.quantity(), unit, item.getBaseUnit());
            BigDecimal gross = line.quantity().multiply(line.purchaseRate()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineDiscount = money(line.discount());
            if (lineDiscount.compareTo(gross) > 0) {
                throw ApiException.badRequest("Discount cannot exceed the line amount for " + item.getItemName());
            }
            rawLines.add(new RawLine(item, unit, line.quantity().setScale(3, RoundingMode.HALF_UP),
                    line.purchaseRate().setScale(2, RoundingMode.HALF_UP), baseQuantity, gross, lineDiscount));
            subtotal = subtotal.add(gross);
        }
        BigDecimal headerDiscount = money(request.discount());
        if (headerDiscount.compareTo(subtotal) > 0) {
            throw ApiException.badRequest("Total discount cannot exceed the purchase amount");
        }
        List<PurchaseDetail> details = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        for (int i = 0; i < rawLines.size(); i++) {
            RawLine raw = rawLines.get(i);
            BigDecimal share = BigDecimal.ZERO;
            if (headerDiscount.compareTo(BigDecimal.ZERO) > 0) {
                if (i == rawLines.size() - 1) {
                    share = headerDiscount.subtract(allocated);
                } else if (subtotal.compareTo(BigDecimal.ZERO) > 0) {
                    share = headerDiscount.multiply(raw.gross()).divide(subtotal, 2, RoundingMode.HALF_UP);
                }
                allocated = allocated.add(share);
            }
            BigDecimal lineDiscount = raw.discount().add(share);
            TaxMaster itemTax = taxes.get(raw.item().getTaxId());
            if (itemTax == null) {
                throw ApiException.badRequest("Tax is not configured for " + raw.item().getItemName());
            }
            GstCalculator.LineTax lineTax = GstCalculator.calculate(raw.gross(), lineDiscount,
                    itemTax.getGstPercentage(), inclusive, interState);
            details.add(PurchaseDetail.builder()
                    .storeId(storeId)
                    .itemId(raw.item().getId())
                    .quantity(raw.quantity())
                    .unit(raw.unit().name())
                    .baseQuantity(raw.baseQuantity())
                    .purchaseRate(raw.rate())
                    .discount(lineDiscount)
                    .tax(lineTax.taxAmount())
                    .totalAmount(lineTax.payableAmount())
                    .taxId(itemTax.getId())
                    .taxNameSnapshot(itemTax.getTaxName())
                    .gstPercentage(itemTax.getGstPercentage())
                    .cgstPercentage(itemTax.getCgstPercentage())
                    .sgstPercentage(itemTax.getSgstPercentage())
                    .igstPercentage(itemTax.getIgstPercentage())
                    .taxableAmount(lineTax.taxableAmount())
                    .cgstAmount(lineTax.cgstAmount())
                    .sgstAmount(lineTax.sgstAmount())
                    .igstAmount(lineTax.igstAmount())
                    .taxAmount(lineTax.taxAmount())
                    .build());
            discount = discount.add(lineDiscount);
            taxable = taxable.add(lineTax.taxableAmount());
            cgst = cgst.add(lineTax.cgstAmount());
            sgst = sgst.add(lineTax.sgstAmount());
            igst = igst.add(lineTax.igstAmount());
            totalTax = totalTax.add(lineTax.taxAmount());
        }
        BigDecimal grand = taxable.add(totalTax).setScale(2, RoundingMode.HALF_UP);
        if (grand.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest("Total discount cannot exceed the purchase amount");
        }
        return new Computed(details, items, subtotal.setScale(2, RoundingMode.HALF_UP),
                discount.setScale(2, RoundingMode.HALF_UP), totalTax.setScale(2, RoundingMode.HALF_UP),
                grand, taxable.setScale(2, RoundingMode.HALF_UP), cgst.setScale(2, RoundingMode.HALF_UP),
                sgst.setScale(2, RoundingMode.HALF_UP), igst.setScale(2, RoundingMode.HALF_UP));
    }

    private record RawLine(Item item, Unit unit, BigDecimal quantity, BigDecimal rate,
                           BigDecimal baseQuantity, BigDecimal gross, BigDecimal discount) {
    }

    private void saveDetails(Purchase purchase, List<PurchaseDetail> details) {
        details.forEach(detail -> detail.setPurchaseId(purchase.getId()));
        detailRepository.saveAll(details);
    }

    private BigDecimal paid(BigDecimal paidAmount, PurchaseStatus status, BigDecimal grandTotal) {
        BigDecimal paid = paidAmount == null
                ? (status == PurchaseStatus.COMPLETED ? grandTotal : BigDecimal.ZERO)
                : paidAmount.setScale(2, RoundingMode.HALF_UP);
        if (paid.compareTo(grandTotal) > 0) {
            throw ApiException.badRequest("Paid amount cannot exceed the grand total");
        }
        return paid;
    }

    private PurchaseResponse toResponse(Purchase purchase, List<PurchaseDetail> details,
                                        Map<Long, Branch> branches, Map<Long, Supplier> suppliers,
                                        Map<Long, Item> items) {
        Branch branch = branches.get(purchase.getBranchId());
        Supplier supplier = suppliers.get(purchase.getSupplierId());
        List<PurchaseResponse.LineResponse> lines = details.stream().map(detail -> {
            Item item = items.get(detail.getItemId());
            return new PurchaseResponse.LineResponse(detail.getId(), detail.getItemId(),
                    item == null ? "" : item.getItemCode(), item == null ? "" : item.getItemName(),
                    detail.getQuantity(), detail.getUnit(), detail.getBaseQuantity(),
                    detail.getPurchaseRate(), detail.getDiscount(), detail.getTaxId(),
                    detail.getTaxNameSnapshot(), detail.getGstPercentage(), detail.getCgstPercentage(),
                    detail.getSgstPercentage(), detail.getIgstPercentage(), detail.getTaxableAmount(),
                    detail.getCgstAmount(), detail.getSgstAmount(), detail.getIgstAmount(),
                    detail.getTaxAmount(), detail.getTotalAmount());
        }).toList();
        return new PurchaseResponse(purchase.getId(), purchase.getPurchaseNumber(),
                purchase.getInvoiceNumber(), purchase.getPurchaseDate(), purchase.getBranchId(),
                branch == null ? "" : branch.getBranchName(), purchase.getSupplierId(),
                supplier == null ? "" : supplier.getSupplierName(), purchase.getSubtotal(),
                purchase.getDiscount(), purchase.getTaxableAmount(), purchase.getCgstAmount(),
                purchase.getSgstAmount(), purchase.getIgstAmount(), purchase.getTax(),
                purchase.getGrandTotal(), purchase.getPaidAmount(), purchase.getBalanceAmount(),
                purchase.getNotes(), purchase.getStatus().name(), purchase.getCreatedAt(), lines);
    }

    private Purchase find(Long id, Long storeId) {
        return purchaseRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Purchase not found"));
    }

    private Supplier supplier(Long id, Long storeId) {
        Supplier supplier = supplierRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.badRequest("Select a valid supplier"));
        if (supplier.getStatus() != StoreStatus.ACTIVE) {
            throw ApiException.badRequest("This supplier is inactive");
        }
        return supplier;
    }

    private Branch activeBranch(Long id, Long storeId) {
        Branch branch = branchRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.badRequest("Select a valid branch"));
        if (branch.getStatus() != StoreStatus.ACTIVE) {
            throw ApiException.badRequest("This branch is inactive");
        }
        return branch;
    }

    private Store store(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> ApiException.notFound("Store not found"));
    }

    private Map<Long, Branch> branches(Long storeId) {
        return branchRepository.findByStoreIdOrderByBranchName(storeId).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));
    }

    private Map<Long, Supplier> suppliers(Long storeId) {
        return supplierRepository.findByStoreIdOrderBySupplierName(storeId).stream()
                .collect(Collectors.toMap(Supplier::getId, Function.identity()));
    }

    private Map<Long, Item> items(Long storeId) {
        return itemRepository.findByStoreIdOrderByItemName(storeId).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
    }

    private Map<Long, TaxMaster> taxes(Long storeId) {
        return taxMasterRepository.findByStoreIdOrderByGstPercentageAscTaxNameAsc(storeId).stream()
                .collect(Collectors.toMap(TaxMaster::getId, Function.identity()));
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private record Computed(List<PurchaseDetail> details, Map<Long, Item> items,
                            BigDecimal subtotal, BigDecimal discount, BigDecimal tax,
                            BigDecimal grandTotal, BigDecimal taxable, BigDecimal cgst,
                            BigDecimal sgst, BigDecimal igst) {
    }
}
