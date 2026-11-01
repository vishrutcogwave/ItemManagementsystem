package com.itemmanagement.service;

import com.itemmanagement.dto.transaction.SaleRequest;
import com.itemmanagement.dto.transaction.SaleResponse;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.BranchInventory;
import com.itemmanagement.entity.Item;
import com.itemmanagement.entity.Sale;
import com.itemmanagement.entity.SalesDetail;
import com.itemmanagement.entity.Store;
import com.itemmanagement.entity.TaxMaster;
import com.itemmanagement.entity.enums.PaymentMethod;
import com.itemmanagement.entity.enums.SaleStatus;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.entity.enums.TransactionType;
import com.itemmanagement.entity.enums.Unit;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.ItemRepository;
import com.itemmanagement.repository.SaleRepository;
import com.itemmanagement.repository.SalesDetailRepository;
import com.itemmanagement.repository.StoreRepository;
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
public class SaleService {

    private final SaleRepository saleRepository;
    private final SalesDetailRepository detailRepository;
    private final BranchRepository branchRepository;
    private final ItemRepository itemRepository;
    private final StoreRepository storeRepository;
    private final TaxMasterRepository taxMasterRepository;
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
            Unit unit = UnitConverter.parse(line.unit());
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
                    .unit(raw.unit().name())
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

    @Transactional
    public SaleResponse cancel(Long id) {
        Long storeId = TenantContext.storeId();
        Sale sale = find(id, storeId);
        if (sale.getStatus() == SaleStatus.CANCELLED) {
            throw ApiException.badRequest("This sale is already cancelled");
        }
        TenantContext.requireBranch(sale.getBranchId());
        List<SalesDetail> details = detailRepository.findBySalesIdOrderByItemId(id);
        Map<Long, Item> items = items(storeId);
        for (SalesDetail detail : details) {
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
        saleRepository.save(sale);
        auditService.log("CANCEL", "SALE", id, "Cancelled sale " + sale.getSalesNumber());
        return toResponse(sale, details, branches(storeId), items);
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
                sale.getStatus().name(), sale.getCreatedAt(), lines);
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

    private record RawLine(Item item, Unit unit, BigDecimal quantity, BigDecimal rate,
                           BigDecimal baseQuantity, BigDecimal gross, BigDecimal discount) {
    }
}
