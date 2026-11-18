package com.itemmanagement.service;

import com.itemmanagement.dto.transaction.SaleCancellationRequest;
import com.itemmanagement.dto.transaction.SaleCancellationResponse;
import com.itemmanagement.dto.transaction.SaleModificationRequest;
import com.itemmanagement.dto.transaction.SaleModificationResponse;
import com.itemmanagement.dto.transaction.SaleRequest;
import com.itemmanagement.dto.transaction.SaleResponse;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.BranchInventory;
import com.itemmanagement.entity.Item;
import com.itemmanagement.entity.Sale;
import com.itemmanagement.entity.SaleCancellation;
import com.itemmanagement.entity.SaleModification;
import com.itemmanagement.entity.SaleModificationItem;
import com.itemmanagement.entity.SalesDetail;
import com.itemmanagement.entity.Store;
import com.itemmanagement.entity.TaxMaster;
import com.itemmanagement.entity.enums.ModificationChangeType;
import com.itemmanagement.entity.enums.PaymentMethod;
import com.itemmanagement.entity.enums.SaleStatus;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.entity.enums.TransactionType;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.ItemRepository;
import com.itemmanagement.repository.SaleCancellationRepository;
import com.itemmanagement.repository.SaleModificationItemRepository;
import com.itemmanagement.repository.SaleModificationRepository;
import com.itemmanagement.repository.SaleRepository;
import com.itemmanagement.repository.SalesDetailRepository;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.repository.TaxMasterRepository;
import com.itemmanagement.repository.UserRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
public class SaleService {

    private static final String STALE_BILL_MESSAGE =
            "This bill was changed by someone else. Reload the bill and try again.";

    private final SaleRepository saleRepository;
    private final SalesDetailRepository detailRepository;
    private final SaleModificationRepository modificationRepository;
    private final SaleModificationItemRepository modificationItemRepository;
    private final SaleCancellationRepository cancellationRepository;
    private final BranchRepository branchRepository;
    private final ItemRepository itemRepository;
    private final StoreRepository storeRepository;
    private final TaxMasterRepository taxMasterRepository;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;
    private final NumberGenerator numberGenerator;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<SaleResponse> list(Long branchId, SaleStatus status, LocalDate from,
                                           LocalDate to, String search, int page, int size) {
        Long storeId = TenantContext.storeId();
        AuthUser user = TenantContext.user();
        if (branchId != null) {
            TenantContext.requireBranch(branchId);
        }
        Page<Sale> sales = saleRepository.search(storeId, branchId,
                user.allBranches() ? null : user.branchIds(), status, from, to, clean(search),
                Pagination.of(page, size));
        Map<Long, Branch> branches = branches(storeId);
        return PageResponse.of(sales, sale -> toResponse(sale, List.of(), branches, Map.of()));
    }

    @Transactional(readOnly = true)
    public SaleResponse get(Long id) {
        Long storeId = TenantContext.storeId();
        Sale sale = find(id, storeId);
        TenantContext.requireBranch(sale.getBranchId());
        return toResponse(sale, detailRepository.findBySalesIdOrderByItemId(id),
                branches(storeId), items(storeId));
    }

    @Transactional
    public SaleResponse create(SaleRequest request) {
        Long storeId = TenantContext.storeId();
        TenantContext.requireBranch(request.branchId());
        Branch branch = activeBranch(request.branchId(), storeId);
        Store store = store(storeId);
        boolean inclusive = store.isTaxInclusive();
        boolean interState = GstCalculator.isInterState(branch.getState(), request.customerState());
        Map<Long, Item> items = items(storeId);
        Map<Long, TaxMaster> taxes = taxes(storeId);
        Set<Long> seen = new HashSet<>();
        List<RawLine> rawLines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (SaleRequest.Line line : request.items()) {
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
            String unit = UnitConverter.parse(line.unit());
            if (unit == null) {
                unit = item.getBaseUnit();
            }
            BigDecimal baseQuantity = UnitConverter.toBase(line.quantity(), unit, item.getBaseUnit());
            BigDecimal rate = Boolean.TRUE.equals(request.pos())
                    ? item.getRate()
                    : line.sellingRate();
            BigDecimal gross = line.quantity().multiply(rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineDiscount = money(line.discount());
            if (lineDiscount.compareTo(gross) > 0) {
                throw ApiException.badRequest("Discount cannot exceed the line amount for " + item.getItemName());
            }
            rawLines.add(new RawLine(item, unit, line.quantity().setScale(3, RoundingMode.HALF_UP),
                    rate.setScale(2, RoundingMode.HALF_UP), baseQuantity, gross, lineDiscount));
            subtotal = subtotal.add(gross);
        }
        BigDecimal headerDiscount = money(request.discount());
        if (headerDiscount.compareTo(subtotal) > 0) {
            throw ApiException.badRequest("Total discount cannot exceed the sale amount");
        }
        List<SalesDetail> details = new ArrayList<>();
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
            details.add(SalesDetail.builder()
                    .storeId(storeId)
                    .itemId(raw.item().getId())
                    .quantity(raw.quantity())
                    .unit(raw.unit())
                    .baseQuantity(raw.baseQuantity())
                    .sellingRate(raw.rate())
                    .costRate(BigDecimal.ZERO)
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
            throw ApiException.badRequest("Total discount cannot exceed the sale amount");
        }
        PaymentMethod method = request.paymentMethod() == null ? PaymentMethod.CASH : request.paymentMethod();
        BigDecimal paid = request.paidAmount() == null
                ? (method == PaymentMethod.CREDIT ? BigDecimal.ZERO : grand)
                : request.paidAmount().setScale(2, RoundingMode.HALF_UP);
        if (paid.compareTo(grand) > 0) {
            throw ApiException.badRequest("Paid amount cannot exceed the grand total");
        }
        Sale sale = saleRepository.save(Sale.builder()
                .storeId(storeId)
                .branchId(branch.getId())
                .salesNumber(numberGenerator.nextSale())
                .salesDate(request.salesDate() == null ? LocalDate.now() : request.salesDate())
                .customerName(blankToNull(request.customerName()))
                .customerMobile(blankToNull(request.customerMobile()))
                .customerState(blankToNull(request.customerState()))
                .subtotal(subtotal.setScale(2, RoundingMode.HALF_UP))
                .discount(discount)
                .tax(totalTax.setScale(2, RoundingMode.HALF_UP))
                .taxableAmount(taxable.setScale(2, RoundingMode.HALF_UP))
                .cgstAmount(cgst.setScale(2, RoundingMode.HALF_UP))
                .sgstAmount(sgst.setScale(2, RoundingMode.HALF_UP))
                .igstAmount(igst.setScale(2, RoundingMode.HALF_UP))
                .grandTotal(grand)
                .paidAmount(paid)
                .balanceAmount(grand.subtract(paid))
                .paymentMethod(method)
                .status(SaleStatus.COMPLETED)
                .createdBy(TenantContext.userId())
                .build());
        for (SalesDetail detail : details) {
            Item item = items.get(detail.getItemId());
            BranchInventory inventory = inventoryService.lockOrCreate(storeId, branch.getId(), item);
            detail.setCostRate(inventory.getAvgCost());
            inventoryService.applyStockOut(inventory, item, detail.getBaseQuantity(),
                    TransactionType.SALE_OUT, "SALE", sale.getId(), sale.getSalesNumber(),
                    "Sale at " + branch.getBranchName());
        }
        details.forEach(detail -> detail.setSalesId(sale.getId()));
        detailRepository.saveAll(details);
        auditService.log("CREATE", "SALE", sale.getId(),
                "Sale " + sale.getSalesNumber() + " at " + branch.getBranchName()
                        + " for " + sale.getGrandTotal());
        return toResponse(sale, details, Map.of(branch.getId(), branch), items);
    }

    @Transactional(readOnly = true)
    public SaleModificationResponse previewModification(Long id, SaleModificationRequest request) {
        Long storeId = TenantContext.storeId();
        Sale sale = find(id, storeId);
        requireModifiable(sale);
        if (request.baseModificationCount() != null
                && request.baseModificationCount() != sale.getModificationCount()) {
            throw ApiException.conflict(STALE_BILL_MESSAGE);
        }
        BillContext context = billContext(sale, storeId);
        Plan plan = plan(sale, request, context);
        SaleResponse saleResponse = toResponse(sale, context.details(),
                Map.of(context.branch().getId(), context.branch()), context.items());
        return modificationResponse(plan.modification(), plan.diffItems(), saleResponse);
    }

    @Transactional
    public SaleModificationResponse modify(Long id, SaleModificationRequest request) {
        Long storeId = TenantContext.storeId();
        Sale sale = saleRepository.findForUpdate(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Sale not found"));
        requireModifiable(sale);
        if (request.baseModificationCount() == null
                || request.baseModificationCount() != sale.getModificationCount()) {
            throw ApiException.conflict(STALE_BILL_MESSAGE);
        }
        BillContext context = billContext(sale, storeId);
        Plan plan = plan(sale, request, context);
        if (plan.modification().getItemsAdded() == 0 && plan.modification().getItemsRemoved() == 0
                && plan.diffItems().stream()
                        .noneMatch(item -> item.getChangeType() == ModificationChangeType.UPDATED)) {
            throw ApiException.badRequest(
                    "This bill already matches your edits. Change a quantity, rate, item or discount before saving.");
        }

        Map<Long, BranchInventory> locked = lockStock(storeId, sale, context.items(), plan.stockDelta());
        String number = numberGenerator.nextSaleModification();
        applyStock(storeId, sale, context.items(), plan.stockDelta(), locked, number);
        writeDetails(storeId, sale, plan, locked);

        SaleModification modification = plan.modification();
        modification.setModificationNumber(number);
        modification.setModifiedBy(TenantContext.userId());
        modificationRepository.save(modification);
        plan.diffItems().forEach(item -> item.setModificationId(modification.getId()));
        modificationItemRepository.saveAll(plan.diffItems());

        int previousCount = sale.getModificationCount();
        sale.setSubtotal(plan.totals().subtotal());
        sale.setDiscount(plan.totals().discount());
        sale.setTaxableAmount(plan.totals().taxable());
        sale.setCgstAmount(plan.totals().cgst());
        sale.setSgstAmount(plan.totals().sgst());
        sale.setIgstAmount(plan.totals().igst());
        sale.setTax(plan.totals().tax());
        sale.setGrandTotal(plan.totals().grand());
        sale.setPaidAmount(plan.paidAfter());
        sale.setBalanceAmount(plan.totals().grand().subtract(plan.paidAfter()));
        sale.setModificationCount(previousCount + 1);
        saleRepository.save(sale);

        auditService.logRequired("MODIFY", "SALE", sale.getId(),
                "Modified sale " + sale.getSalesNumber() + " (" + number + ") from "
                        + plan.modification().getOriginalTotal() + " to "
                        + plan.modification().getModifiedTotal()
                        + (modification.getReason() == null ? "" : ": " + modification.getReason()));

        return modificationResponse(modification, plan.diffItems(),
                toResponse(sale, detailRepository.findBySalesIdOrderByItemId(id),
                        Map.of(context.branch().getId(), context.branch()), context.items()));
    }

    @Transactional(readOnly = true)
    public List<SaleModificationResponse> modifications(Long id) {
        Long storeId = TenantContext.storeId();
        Sale sale = find(id, storeId);
        TenantContext.requireBranch(sale.getBranchId());
        SaleResponse saleResponse = toResponse(sale, detailRepository.findBySalesIdOrderByItemId(id),
                branches(storeId), items(storeId));
        return modificationRepository.findByStoreIdAndSaleIdOrderByModifiedAtDesc(storeId, id).stream()
                .map(modification -> modificationResponse(modification,
                        modificationItemRepository.findByModificationId(modification.getId()), saleResponse))
                .toList();
    }

    @Transactional
    public SaleCancellationResponse cancel(Long id, SaleCancellationRequest request) {
        Long storeId = TenantContext.storeId();
        Sale sale = saleRepository.findForUpdate(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Sale not found"));
        if (sale.getStatus() == SaleStatus.CANCELLED) {
            throw ApiException.conflict("This bill is already cancelled");
        }
        TenantContext.requireBranch(sale.getBranchId());
        String reason = blankToNull(request.reason());
        if (reason == null) {
            throw ApiException.badRequest("Cancellation reason is required");
        }
        List<SalesDetail> details = detailRepository.findBySalesIdOrderByItemId(id);
        Map<Long, Item> items = items(storeId);
        List<SalesDetail> restored = details.stream()
                .filter(detail -> detail.getBaseQuantity().compareTo(BigDecimal.ZERO) > 0)
                .sorted((left, right) -> left.getItemId().compareTo(right.getItemId()))
                .toList();
        for (SalesDetail detail : restored) {
            Item item = items.get(detail.getItemId());
            if (item == null) {
                continue;
            }
            BranchInventory inventory = inventoryService.lockOrCreate(storeId, sale.getBranchId(), item);
            inventoryService.applyStockIn(inventory, item, detail.getBaseQuantity(), null,
                    TransactionType.SALE_REVERSAL, "SALE", sale.getId(), sale.getSalesNumber(),
                    "Cancellation of sale " + sale.getSalesNumber());
        }
        sale.setStatus(SaleStatus.CANCELLED);
        sale.setCancelledAt(Instant.now());
        sale.setCancelledBy(TenantContext.userId());
        sale.setCancellationReason(reason);
        saleRepository.save(sale);

        String number = numberGenerator.nextSaleCancellation();
        SaleCancellation cancellation = cancellationRepository.save(SaleCancellation.builder()
                .storeId(storeId)
                .saleId(sale.getId())
                .cancellationNumber(number)
                .reason(reason)
                .cancelledBy(TenantContext.userId())
                .saleTotal(sale.getGrandTotal())
                .paidAmount(sale.getPaidAmount())
                .restoredItems(restored.size())
                .build());
        auditService.logRequired("CANCEL", "SALE", id,
                "Cancelled sale " + sale.getSalesNumber() + " (" + number + "): " + reason);
        return cancellationResponse(cancellation, sale);
    }

    @Transactional(readOnly = true)
    public SaleCancellationResponse cancellation(Long id) {
        Long storeId = TenantContext.storeId();
        Sale sale = find(id, storeId);
        TenantContext.requireBranch(sale.getBranchId());
        SaleCancellation cancellation = cancellationRepository.findByStoreIdAndSaleId(storeId, id)
                .orElseThrow(() -> ApiException.notFound("This bill is not cancelled"));
        return cancellationResponse(cancellation, sale);
    }

    private void requireModifiable(Sale sale) {
        TenantContext.requireBranch(sale.getBranchId());
        if (sale.getStatus() == SaleStatus.CANCELLED) {
            throw ApiException.badRequest("A cancelled bill cannot be modified");
        }
    }

    private BillContext billContext(Sale sale, Long storeId) {
        Branch branch = branchRepository.findByIdAndStoreId(sale.getBranchId(), storeId)
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
        return new BillContext(store(storeId), branch,
                detailRepository.findBySalesIdOrderByItemId(sale.getId()),
                items(storeId), taxes(storeId));
    }

    private Plan plan(Sale sale, SaleModificationRequest request, BillContext context) {
        boolean inclusive = context.store().isTaxInclusive();
        boolean interState = GstCalculator.isInterState(context.branch().getState(), sale.getCustomerState());
        Map<Long, SalesDetail> existing = new LinkedHashMap<>();
        for (SalesDetail detail : context.details()) {
            existing.put(detail.getItemId(), detail);
        }

        Set<Long> seen = new HashSet<>();
        List<PlannedLine> lines = new ArrayList<>();
        List<SaleModificationItem> diffs = new ArrayList<>();
        Map<Long, BigDecimal> stockDelta = new HashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal originalQuantity = BigDecimal.ZERO;
        BigDecimal modifiedQuantity = BigDecimal.ZERO;
        BigDecimal increased = BigDecimal.ZERO;
        BigDecimal decreased = BigDecimal.ZERO;
        int added = 0;
        int removed = 0;
        int updated = 0;

        for (SaleModificationRequest.Line line : request.items()) {
            if (!seen.add(line.itemId())) {
                throw ApiException.badRequest("The same item cannot be added twice");
            }
            Item item = context.items().get(line.itemId());
            if (item == null) {
                throw ApiException.badRequest("Select a valid item");
            }
            if (item.getStatus() != StoreStatus.ACTIVE) {
                throw ApiException.badRequest(item.getItemName() + " is inactive");
            }
            String unit = UnitConverter.parse(line.unit());
            if (unit == null) {
                unit = item.getBaseUnit();
            }
            BigDecimal quantity = line.quantity().setScale(3, RoundingMode.HALF_UP);
            BigDecimal baseQuantity = UnitConverter.toBase(line.quantity(), unit, item.getBaseUnit());
            BigDecimal rate = line.sellingRate().setScale(2, RoundingMode.HALF_UP);
            BigDecimal gross = quantity.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineDiscount = money(line.discount());
            if (lineDiscount.compareTo(gross) > 0) {
                throw ApiException.badRequest("Discount cannot exceed the line amount for " + item.getItemName());
            }
            TaxMaster itemTax = context.taxes().get(item.getTaxId());
            if (itemTax == null) {
                throw ApiException.badRequest("Tax is not configured for " + item.getItemName());
            }
            GstCalculator.LineTax lineTax = GstCalculator.calculate(gross, lineDiscount,
                    itemTax.getGstPercentage(), inclusive, interState);

            SalesDetail previous = existing.get(item.getId());
            BigDecimal previousQuantity = previous == null ? BigDecimal.ZERO : previous.getQuantity();
            BigDecimal previousBaseQuantity = previous == null ? BigDecimal.ZERO : previous.getBaseQuantity();
            BigDecimal previousRate = previous == null ? BigDecimal.ZERO : previous.getSellingRate();
            BigDecimal previousAmount = previous == null ? BigDecimal.ZERO : previous.getTotalAmount();
            BigDecimal newAmount = lineTax.payableAmount();

            BigDecimal delta = baseQuantity.subtract(previousBaseQuantity);
            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                stockDelta.merge(item.getId(), delta, BigDecimal::add);
                if (delta.signum() > 0) increased = increased.add(delta);
                else decreased = decreased.add(delta.negate());
            }
            originalQuantity = originalQuantity.add(previousBaseQuantity);
            modifiedQuantity = modifiedQuantity.add(baseQuantity);
            subtotal = subtotal.add(gross);
            discount = discount.add(lineDiscount);
            taxable = taxable.add(lineTax.taxableAmount());
            cgst = cgst.add(lineTax.cgstAmount());
            sgst = sgst.add(lineTax.sgstAmount());
            igst = igst.add(lineTax.igstAmount());
            tax = tax.add(lineTax.taxAmount());

            ModificationChangeType changeType;
            if (previous == null) {
                changeType = ModificationChangeType.ADDED;
                added++;
            } else if (previous.getQuantity().compareTo(quantity) != 0
                    || previous.getSellingRate().compareTo(rate) != 0
                    || previous.getDiscount().compareTo(lineDiscount) != 0
                    || !previous.getUnit().equals(unit)
                    || previous.getTotalAmount().compareTo(newAmount) != 0) {
                changeType = ModificationChangeType.UPDATED;
                updated++;
            } else {
                changeType = null;
            }
            if (changeType != null) {
                diffs.add(diff(sale.getStoreId(), item, unit, changeType, previousQuantity, quantity,
                        previousRate, rate, previousAmount, newAmount, lineTax.taxAmount()));
            }
            lines.add(new PlannedLine(item, previous, unit, quantity, baseQuantity, rate,
                    lineDiscount, itemTax, lineTax));
        }

        List<SalesDetail> removedDetails = new ArrayList<>();
        for (SalesDetail previous : context.details()) {
            if (seen.contains(previous.getItemId())) {
                continue;
            }
            Item item = context.items().get(previous.getItemId());
            if (item == null) {
                throw ApiException.badRequest(
                        "An item on this bill is no longer available. Cancel the bill instead of modifying it.");
            }
            removedDetails.add(previous);
            removed++;
            BigDecimal delta = previous.getBaseQuantity().negate();
            stockDelta.merge(item.getId(), delta, BigDecimal::add);
            decreased = decreased.add(previous.getBaseQuantity());
            originalQuantity = originalQuantity.add(previous.getBaseQuantity());
            diffs.add(diff(sale.getStoreId(), item, previous.getUnit(), ModificationChangeType.REMOVED,
                    previous.getQuantity(), BigDecimal.ZERO, previous.getSellingRate(), BigDecimal.ZERO,
                    previous.getTotalAmount(), BigDecimal.ZERO, BigDecimal.ZERO));
        }

        BigDecimal grand = taxable.add(tax).setScale(2, RoundingMode.HALF_UP);
        BigDecimal originalTotal = sale.getGrandTotal();
        BigDecimal paid = sale.getPaidAmount();
        BigDecimal paidAfter = paid.min(grand);
        BigDecimal previousBalance = sale.getBalanceAmount();
        BigDecimal newBalance = grand.subtract(paidAfter);
        BigDecimal additionalPayable = newBalance.subtract(previousBalance).max(BigDecimal.ZERO);
        BigDecimal refundDue = paid.subtract(grand).max(BigDecimal.ZERO);

        SaleModification modification = SaleModification.builder()
                .storeId(sale.getStoreId())
                .saleId(sale.getId())
                .reason(blankToNull(request.reason()))
                .originalTotal(originalTotal)
                .modifiedTotal(grand)
                .differenceAmount(grand.subtract(originalTotal))
                .originalQuantity(originalQuantity)
                .modifiedQuantity(modifiedQuantity)
                .quantityIncreased(increased)
                .quantityDecreased(decreased)
                .itemsAdded(added)
                .itemsRemoved(removed)
                .additionalPayable(additionalPayable)
                .refundDue(refundDue)
                .build();

        return new Plan(modification, diffs, lines, removedDetails, stockDelta,
                new BillTotals(subtotal.setScale(2, RoundingMode.HALF_UP), discount.setScale(2, RoundingMode.HALF_UP),
                        taxable.setScale(2, RoundingMode.HALF_UP), cgst.setScale(2, RoundingMode.HALF_UP),
                        sgst.setScale(2, RoundingMode.HALF_UP), igst.setScale(2, RoundingMode.HALF_UP),
                        tax.setScale(2, RoundingMode.HALF_UP), grand), paidAfter);
    }

    private SaleModificationItem diff(Long storeId, Item item, String unit, ModificationChangeType changeType,
                                      BigDecimal originalQuantity, BigDecimal modifiedQuantity,
                                      BigDecimal originalRate, BigDecimal modifiedRate,
                                      BigDecimal originalAmount, BigDecimal modifiedAmount,
                                      BigDecimal taxAmount) {
        return SaleModificationItem.builder()
                .storeId(storeId)
                .itemId(item.getId())
                .itemCode(item.getItemCode())
                .itemName(item.getItemName())
                .unit(unit)
                .changeType(changeType)
                .originalQuantity(originalQuantity.setScale(3, RoundingMode.HALF_UP))
                .modifiedQuantity(modifiedQuantity.setScale(3, RoundingMode.HALF_UP))
                .originalRate(originalRate.setScale(2, RoundingMode.HALF_UP))
                .modifiedRate(modifiedRate.setScale(2, RoundingMode.HALF_UP))
                .originalAmount(originalAmount.setScale(2, RoundingMode.HALF_UP))
                .modifiedAmount(modifiedAmount.setScale(2, RoundingMode.HALF_UP))
                .differenceAmount(modifiedAmount.subtract(originalAmount).setScale(2, RoundingMode.HALF_UP))
                .taxAmount(taxAmount.setScale(2, RoundingMode.HALF_UP))
                .build();
    }

    private Map<Long, BranchInventory> lockStock(Long storeId, Sale sale, Map<Long, Item> items,
                                                 Map<Long, BigDecimal> deltas) {
        Map<Long, BranchInventory> locked = new HashMap<>();
        for (Long itemId : deltas.keySet().stream().sorted().toList()) {
            BigDecimal delta = deltas.get(itemId);
            if (delta.signum() <= 0) {
                continue;
            }
            Item item = items.get(itemId);
            BranchInventory inventory = inventoryService.lockOrCreate(storeId, sale.getBranchId(), item);
            if (inventory.getCurrentStock().compareTo(delta) < 0) {
                throw ApiException.badRequest("Insufficient stock for " + item.getItemName()
                        + ". Available: " + inventory.getCurrentStock().stripTrailingZeros().toPlainString()
                        + " " + item.getBaseUnit() + ", required: "
                        + delta.stripTrailingZeros().toPlainString() + " " + item.getBaseUnit());
            }
            locked.put(itemId, inventory);
        }
        return locked;
    }

    private void applyStock(Long storeId, Sale sale, Map<Long, Item> items,
                            Map<Long, BigDecimal> deltas, Map<Long, BranchInventory> locked,
                            String modificationNumber) {
        for (Long itemId : deltas.keySet().stream().sorted().toList()) {
            BigDecimal delta = deltas.get(itemId);
            if (delta.signum() == 0) {
                continue;
            }
            Item item = items.get(itemId);
            BranchInventory inventory = locked.get(itemId);
            String notes = "Bill modification " + modificationNumber + " of " + sale.getSalesNumber();
            if (inventory == null) {
                inventory = inventoryService.lockOrCreate(storeId, sale.getBranchId(), item);
            }
            if (delta.signum() > 0) {
                inventoryService.applyStockOut(inventory, item, delta, TransactionType.SALE_OUT,
                        "SALE", sale.getId(), sale.getSalesNumber(), notes);
            } else {
                inventoryService.applyStockIn(inventory, item, delta.negate(), null,
                        TransactionType.SALE_REVERSAL, "SALE", sale.getId(), sale.getSalesNumber(), notes);
            }
        }
    }

    private void writeDetails(Long storeId, Sale sale, Plan plan, Map<Long, BranchInventory> locked) {
        for (PlannedLine line : plan.lines()) {
            SalesDetail detail = line.existing();
            if (detail == null) {
                BranchInventory inventory = locked.get(line.item().getId());
                detail = SalesDetail.builder()
                        .storeId(storeId)
                        .salesId(sale.getId())
                        .itemId(line.item().getId())
                        .costRate(inventory == null ? BigDecimal.ZERO : inventory.getAvgCost())
                        .build();
            }
            detail.setQuantity(line.quantity());
            detail.setUnit(line.unit());
            detail.setBaseQuantity(line.baseQuantity());
            detail.setSellingRate(line.rate());
            detail.setDiscount(line.discount());
            detail.setTaxId(line.tax().getId());
            detail.setTaxNameSnapshot(line.tax().getTaxName());
            detail.setGstPercentage(line.tax().getGstPercentage());
            detail.setCgstPercentage(line.tax().getCgstPercentage());
            detail.setSgstPercentage(line.tax().getSgstPercentage());
            detail.setIgstPercentage(line.tax().getIgstPercentage());
            detail.setTaxableAmount(line.lineTax().taxableAmount());
            detail.setCgstAmount(line.lineTax().cgstAmount());
            detail.setSgstAmount(line.lineTax().sgstAmount());
            detail.setIgstAmount(line.lineTax().igstAmount());
            detail.setTaxAmount(line.lineTax().taxAmount());
            detail.setTax(line.lineTax().taxAmount());
            detail.setTotalAmount(line.lineTax().payableAmount());
            detailRepository.save(detail);
        }
        for (SalesDetail removed : plan.removed()) {
            detailRepository.delete(removed);
        }
    }

    private SaleModificationResponse modificationResponse(SaleModification modification,
                                                          List<SaleModificationItem> items,
                                                          SaleResponse sale) {
        List<SaleModificationResponse.LineDiff> lines = items.stream()
                .map(item -> new SaleModificationResponse.LineDiff(item.getItemId(), item.getItemCode(),
                        item.getItemName(), item.getUnit(), item.getChangeType().name(),
                        item.getOriginalQuantity(), item.getModifiedQuantity(), item.getOriginalRate(),
                        item.getModifiedRate(), item.getOriginalAmount(), item.getModifiedAmount(),
                        item.getDifferenceAmount(), item.getTaxAmount()))
                .toList();
        int updated = (int) items.stream()
                .filter(item -> item.getChangeType() == ModificationChangeType.UPDATED).count();
        return new SaleModificationResponse(modification.getId(), modification.getModificationNumber(),
                modification.getModifiedAt(), modification.getReason(),
                modification.getOriginalTotal(), modification.getModifiedTotal(),
                modification.getDifferenceAmount(), modification.getAdditionalPayable(),
                modification.getRefundDue(), modification.getOriginalQuantity(),
                modification.getModifiedQuantity(), modification.getQuantityIncreased(),
                modification.getQuantityDecreased(), modification.getItemsAdded(),
                modification.getItemsRemoved(), updated, lines, sale);
    }

    private SaleCancellationResponse cancellationResponse(SaleCancellation cancellation, Sale sale) {
        String cancelledByName = cancellation.getCancelledBy() == null ? null
                : userRepository.findByIdAndStoreId(cancellation.getCancelledBy(), sale.getStoreId())
                        .map(user -> user.getFullName()).orElse(null);
        return new SaleCancellationResponse(sale.getId(), sale.getSalesNumber(),
                cancellation.getCancellationNumber(), cancellation.getReason(),
                cancellation.getCancelledAt(), cancellation.getCancelledBy(), cancelledByName,
                cancellation.getSaleTotal(), cancellation.getPaidAmount(),
                cancellation.getSaleTotal().subtract(cancellation.getPaidAmount()),
                cancellation.getRestoredItems(), sale.getStatus().name());
    }

    private SaleResponse toResponse(Sale sale, List<SalesDetail> details,
                                    Map<Long, Branch> branches, Map<Long, Item> items) {
        Branch branch = branches.get(sale.getBranchId());
        List<SaleResponse.LineResponse> lines = details.stream().map(detail -> {
            Item item = items.get(detail.getItemId());
            return new SaleResponse.LineResponse(detail.getId(), detail.getItemId(),
                    item == null ? "" : item.getItemCode(), item == null ? "" : item.getItemName(),
                    detail.getQuantity(), detail.getUnit(), detail.getBaseQuantity(),
                    detail.getSellingRate(), detail.getCostRate(), detail.getDiscount(),
                    detail.getTaxId(), detail.getTaxNameSnapshot(), detail.getGstPercentage(),
                    detail.getCgstPercentage(), detail.getSgstPercentage(), detail.getIgstPercentage(),
                    detail.getTaxableAmount(), detail.getCgstAmount(), detail.getSgstAmount(),
                    detail.getIgstAmount(), detail.getTaxAmount(), detail.getTotalAmount());
        }).toList();
        return new SaleResponse(sale.getId(), sale.getSalesNumber(), sale.getSalesDate(),
                sale.getBranchId(), branch == null ? "" : branch.getBranchName(),
                sale.getCustomerName(), sale.getCustomerMobile(), sale.getCustomerState(),
                sale.getSubtotal(), sale.getDiscount(), sale.getTaxableAmount(), sale.getCgstAmount(),
                sale.getSgstAmount(), sale.getIgstAmount(), sale.getTax(), sale.getGrandTotal(),
                sale.getPaidAmount(), sale.getBalanceAmount(), sale.getPaymentMethod().name(),
                sale.getStatus().name(), sale.getModificationCount(), sale.getCancelledAt(),
                sale.getCancellationReason(), sale.getCreatedAt(), lines);
    }

    private Sale find(Long id, Long storeId) {
        return saleRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Sale not found"));
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

    private record RawLine(Item item, String unit, BigDecimal quantity, BigDecimal rate,
                           BigDecimal baseQuantity, BigDecimal gross, BigDecimal discount) {
    }

    private record BillContext(Store store, Branch branch, List<SalesDetail> details,
                               Map<Long, Item> items, Map<Long, TaxMaster> taxes) {
    }

    private record BillTotals(BigDecimal subtotal, BigDecimal discount, BigDecimal taxable,
                              BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal tax,
                              BigDecimal grand) {
    }

    private record PlannedLine(Item item, SalesDetail existing, String unit, BigDecimal quantity,
                               BigDecimal baseQuantity, BigDecimal rate, BigDecimal discount,
                               TaxMaster tax, GstCalculator.LineTax lineTax) {
    }

    private record Plan(SaleModification modification, List<SaleModificationItem> diffItems,
                        List<PlannedLine> lines, List<SalesDetail> removed,
                        Map<Long, BigDecimal> stockDelta, BillTotals totals, BigDecimal paidAfter) {
    }
}
