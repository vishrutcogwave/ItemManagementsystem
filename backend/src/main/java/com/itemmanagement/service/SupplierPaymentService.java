package com.itemmanagement.service;

import com.itemmanagement.dto.report.SupplierBillReportResponse;
import com.itemmanagement.dto.report.SupplierLedgerReportResponse;
import com.itemmanagement.dto.report.SupplierOutstandingReportResponse;
import com.itemmanagement.dto.report.SupplierPaymentReportResponse;
import com.itemmanagement.dto.supplier.SupplierBillResponse;
import com.itemmanagement.dto.supplier.SupplierOutstandingResponse;
import com.itemmanagement.dto.supplier.SupplierPaymentRequest;
import com.itemmanagement.dto.supplier.SupplierPaymentResponse;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.Purchase;
import com.itemmanagement.entity.Supplier;
import com.itemmanagement.entity.SupplierPayment;
import com.itemmanagement.entity.SupplierPaymentAllocation;
import com.itemmanagement.entity.enums.PurchaseStatus;
import com.itemmanagement.entity.enums.SettlementStatus;
import com.itemmanagement.entity.enums.SupplierPaymentMethod;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.PurchaseRepository;
import com.itemmanagement.repository.SupplierPaymentAllocationRepository;
import com.itemmanagement.repository.SupplierPaymentRepository;
import com.itemmanagement.repository.SupplierRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PageResponse;
import com.itemmanagement.util.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SupplierPaymentService {

    private final SupplierPaymentRepository paymentRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseRepository purchaseRepository;
    private final BranchRepository branchRepository;
    private final NumberGenerator numberGenerator;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<SupplierOutstandingResponse> outstanding(String search, Long branchId,
                                                                 int page, int size) {
        Long storeId = TenantContext.storeId();
        List<SupplierAggregate> aggregates = aggregates(storeId, branchScope(branchId), null, clean(search));
        List<SupplierOutstandingResponse> rows = aggregates.stream()
                .map(aggregate -> new SupplierOutstandingResponse(aggregate.supplierId,
                        supplierCode(aggregate.supplierId), aggregate.supplierName,
                        money(aggregate.totalPurchase), money(aggregate.totalPaid),
                        money(aggregate.outstanding()), aggregate.billCount, aggregate.pendingBillCount))
                .toList();
        return page(rows, page, size);
    }

    @Transactional(readOnly = true)
    public List<SupplierBillResponse> bills(Long supplierId, Long branchId) {
        Long storeId = TenantContext.storeId();
        Supplier supplier = supplierRepository.findByIdAndStoreId(supplierId, storeId)
                .orElseThrow(() -> ApiException.notFound("Supplier not found"));
        List<Long> branchIds = branchScope(branchId);
        Map<Long, Branch> branches = branchMap(storeId);
        return purchaseRepository.findSupplierBills(storeId, supplier.getId(), branchIds,
                        PurchaseStatus.COMPLETED)
                .stream()
                .filter(purchase -> accessible(purchase))
                .filter(purchase -> branchId == null || purchase.getBranchId().equals(branchId))
                .map(purchase -> new SupplierBillResponse(purchase.getId(), purchase.getPurchaseNumber(),
                        purchase.getInvoiceNumber(), purchase.getPurchaseDate(),
                        branchName(branches, purchase.getBranchId()), money(purchase.getGrandTotal()),
                        money(purchase.getPaidAmount()), money(balanceOf(purchase)),
                        settlement(purchase).name()))
                .toList();
    }

    @Transactional
    public SupplierPaymentResponse create(SupplierPaymentRequest request) {
        Long storeId = TenantContext.storeId();
        AuthUser user = TenantContext.user();
        Supplier supplier = supplierRepository.lockByIdAndStoreId(request.supplierId(), storeId)
                .orElseThrow(() -> ApiException.badRequest("Select a valid supplier"));

        String clientReference = blankToNull(request.clientReference());
        if (clientReference != null) {
            Optional<SupplierPayment> existing = paymentRepository
                    .findByStoreIdAndClientReference(storeId, clientReference);
            if (existing.isPresent()) {
                return toResponses(List.of(existing.get())).get(0);
            }
        }

        LocalDate paymentDate = request.paymentDate();
        if (paymentDate.isAfter(LocalDate.now())) {
            throw ApiException.badRequest("Payment date cannot be in the future");
        }
        BigDecimal amount = money(request.amount());
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Payment amount must be greater than zero");
        }

        List<Long> purchaseIds = request.purchaseIds() == null ? List.of() : request.purchaseIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (purchaseIds.isEmpty()) {
            throw ApiException.badRequest("Select at least one purchase bill");
        }
        List<Purchase> locked = purchaseRepository.lockByIds(storeId, purchaseIds);
        if (locked.size() != purchaseIds.size()) {
            throw ApiException.badRequest("One or more selected purchase bills were not found");
        }
        List<Purchase> bills = locked.stream()
                .sorted(Comparator.comparing(Purchase::getPurchaseDate).thenComparing(Purchase::getId))
                .toList();
        BigDecimal outstanding = BigDecimal.ZERO;
        for (Purchase bill : bills) {
            if (!bill.getSupplierId().equals(supplier.getId())) {
                throw ApiException.badRequest("Bill " + bill.getPurchaseNumber()
                        + " does not belong to the selected supplier");
            }
            if (bill.getStatus() != PurchaseStatus.COMPLETED) {
                throw ApiException.badRequest("Bill " + bill.getPurchaseNumber() + " is not completed");
            }
            if (!accessible(bill)) {
                throw ApiException.forbidden("You do not have access to bill " + bill.getPurchaseNumber());
            }
            BigDecimal balance = balanceOf(bill);
            if (balance.signum() <= 0) {
                throw ApiException.badRequest("Bill " + bill.getPurchaseNumber() + " is already fully paid");
            }
            outstanding = outstanding.add(balance);
        }
        if (amount.compareTo(outstanding) > 0) {
            throw ApiException.badRequest("Payment amount cannot exceed the outstanding balance of the selected bills ("
                    + money(outstanding).toPlainString() + ")");
        }

        SupplierPaymentMethod method = request.paymentMethod() == null
                ? SupplierPaymentMethod.CASH
                : request.paymentMethod();
        SupplierPayment payment = paymentRepository.save(SupplierPayment.builder()
                .storeId(storeId)
                .supplierId(supplier.getId())
                .paymentNumber(numberGenerator.nextSupplierPayment())
                .paymentDate(paymentDate)
                .paymentMethod(method)
                .amount(amount)
                .referenceNumber(blankToNull(request.referenceNumber()))
                .remarks(blankToNull(request.remarks()))
                .clientReference(clientReference)
                .createdBy(user.userId())
                .createdByName(displayName(user))
                .build());

        BigDecimal remaining = amount;
        List<SupplierPaymentAllocation> allocations = new ArrayList<>();
        for (Purchase bill : bills) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal allocation = remaining.min(balanceOf(bill)).setScale(2, RoundingMode.HALF_UP);
            if (allocation.signum() <= 0) {
                continue;
            }
            allocations.add(SupplierPaymentAllocation.builder()
                    .storeId(storeId)
                    .paymentId(payment.getId())
                    .purchaseId(bill.getId())
                    .amount(allocation)
                    .build());
            bill.setPaidAmount(money(bill.getPaidAmount()).add(allocation));
            bill.setBalanceAmount(money(bill.getGrandTotal().subtract(bill.getPaidAmount())));
            remaining = remaining.subtract(allocation);
        }
        if (remaining.signum() > 0) {
            throw ApiException.badRequest("Payment could not be allocated to the selected bills");
        }
        allocationRepository.saveAll(allocations);
        purchaseRepository.saveAll(bills);

        Set<Long> billIds = allocations.stream().map(SupplierPaymentAllocation::getPurchaseId)
                .collect(Collectors.toSet());
        auditService.log("CREATE", "SUPPLIER_PAYMENT", payment.getId(),
                "Payment " + payment.getPaymentNumber() + " to " + supplier.getSupplierName()
                        + " of " + amount.toPlainString() + " across " + billIds.size() + " bill(s)");
        return toResponse(payment, supplier, allocations, purchasesById(bills));
    }

    @Transactional(readOnly = true)
    public PageResponse<SupplierPaymentResponse> list(Long supplierId, LocalDate from, LocalDate to,
                                                      String search, int page, int size) {
        Long storeId = TenantContext.storeId();
        List<Long> branchIds = branchScope(null);
        var result = searchPayments(storeId, supplierId, from, to, branchIds, search,
                Pagination.of(page, size));
        return mapPage(result);
    }

    @Transactional(readOnly = true)
    public SupplierPaymentResponse get(Long id) {
        Long storeId = TenantContext.storeId();
        SupplierPayment payment = paymentRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Payment not found"));
        if (!TenantContext.user().allBranches()) {
            List<SupplierPaymentAllocation> allocations = allocationRepository.findByPaymentId(id);
            Map<Long, Purchase> purchases = purchasesById(allocations, storeId);
            boolean accessible = allocations.stream()
                    .map(allocation -> purchases.get(allocation.getPurchaseId()))
                    .filter(Objects::nonNull)
                    .anyMatch(this::accessible);
            if (!accessible) {
                throw ApiException.notFound("Payment not found");
            }
        }
        return toResponses(List.of(payment)).get(0);
    }

    // ---------- reports ----------

    @Transactional(readOnly = true)
    public SupplierOutstandingReportResponse supplierOutstandingReport(Long supplierId, String search, Long branchId) {
        Long storeId = TenantContext.storeId();
        List<SupplierAggregate> aggregates = aggregates(storeId, branchScope(branchId), supplierId, clean(search));
        List<SupplierOutstandingReportResponse.Row> rows = aggregates.stream()
                .map(aggregate -> new SupplierOutstandingReportResponse.Row(aggregate.supplierId,
                        supplierCode(aggregate.supplierId), aggregate.supplierName,
                        money(aggregate.totalPurchase), money(aggregate.totalPaid),
                        money(aggregate.outstanding()), aggregate.billCount, aggregate.pendingBillCount))
                .toList();
        BigDecimal totalPurchase = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        for (SupplierAggregate aggregate : aggregates) {
            totalPurchase = totalPurchase.add(aggregate.totalPurchase);
            totalPaid = totalPaid.add(aggregate.totalPaid);
            outstanding = outstanding.add(aggregate.outstanding());
        }
        return new SupplierOutstandingReportResponse(rows,
                new SupplierOutstandingReportResponse.Totals(money(totalPurchase), money(totalPaid),
                        money(outstanding), rows.size()));
    }

    @Transactional(readOnly = true)
    public SupplierBillReportResponse supplierBillsReport(Long supplierId, LocalDate from, LocalDate to,
                                                          Long branchId, String settlement, String search) {
        Long storeId = TenantContext.storeId();
        DateRange range = range(from, to);
        List<Long> branchIds = branchScope(branchId);
        Map<Long, Supplier> suppliers = supplierMap(storeId);
        Map<Long, Branch> branches = branchMap(storeId);
        String needle = clean(search);
        List<SupplierBillReportResponse.Row> rows = purchaseRepository
                .findForPeriod(storeId, branchIds, null, PurchaseStatus.COMPLETED, range.from(), range.to())
                .stream()
                .filter(purchase -> accessible(purchase))
                .filter(purchase -> supplierId == null || purchase.getSupplierId().equals(supplierId))
                .filter(purchase -> matchesBill(purchase, suppliers.get(purchase.getSupplierId()), needle))
                .map(purchase -> {
                    Supplier supplier = suppliers.get(purchase.getSupplierId());
                    return new SupplierBillReportResponse.Row(purchase.getSupplierId(),
                            supplierCode(purchase.getSupplierId()),
                            supplier == null ? "" : supplier.getSupplierName(),
                            purchase.getPurchaseNumber(), purchase.getInvoiceNumber(),
                            purchase.getPurchaseDate(), branchName(branches, purchase.getBranchId()),
                            money(purchase.getGrandTotal()), money(purchase.getPaidAmount()),
                            money(balanceOf(purchase)), settlement(purchase).name());
                })
                .filter(row -> settlement == null || settlement.isBlank()
                        || row.settlementStatus().equalsIgnoreCase(settlement.trim()))
                .sorted(Comparator.comparing(SupplierBillReportResponse.Row::purchaseDate).reversed()
                        .thenComparing(SupplierBillReportResponse.Row::purchaseNumber))
                .toList();
        BigDecimal grand = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal balance = BigDecimal.ZERO;
        for (SupplierBillReportResponse.Row row : rows) {
            grand = grand.add(row.grandTotal());
            paid = paid.add(row.paidAmount());
            balance = balance.add(row.balanceAmount());
        }
        return new SupplierBillReportResponse(rows,
                new SupplierBillReportResponse.Totals(money(grand), money(paid), money(balance), rows.size()));
    }

    @Transactional(readOnly = true)
    public SupplierPaymentReportResponse supplierPaymentsReport(LocalDate from, LocalDate to,
                                                                Long supplierId, Long branchId,
                                                                String search) {
        Long storeId = TenantContext.storeId();
        DateRange range = range(from, to);
        List<Long> branchIds = branchScope(branchId);
        List<SupplierPayment> payments = searchPayments(storeId, supplierId, range.from(), range.to(),
                branchIds, search, Pageable.unpaged()).getContent();
        Map<Long, Supplier> suppliers = supplierMap(storeId);
        Map<Long, List<SupplierPaymentAllocation>> allocations = allocationsByPayment(payments);
        Map<Long, Purchase> purchases = purchasesById(
                allocations.values().stream().flatMap(List::stream).toList(), storeId);
        List<SupplierPaymentReportResponse.Row> rows = payments.stream()
                .map(payment -> {
                    Supplier supplier = suppliers.get(payment.getSupplierId());
                    String bills = allocations.getOrDefault(payment.getId(), List.of()).stream()
                            .map(allocation -> purchases.get(allocation.getPurchaseId()))
                            .filter(Objects::nonNull)
                            .map(Purchase::getPurchaseNumber)
                            .collect(Collectors.joining(", "));
                    return new SupplierPaymentReportResponse.Row(payment.getPaymentNumber(),
                            payment.getPaymentDate(), supplierCode(payment.getSupplierId()),
                            supplier == null ? "" : supplier.getSupplierName(), bills,
                            payment.getPaymentMethod().name(), money(payment.getAmount()),
                            payment.getReferenceNumber(), payment.getRemarks(),
                            payment.getCreatedByName());
                })
                .toList();
        BigDecimal total = BigDecimal.ZERO;
        for (SupplierPaymentReportResponse.Row row : rows) {
            total = total.add(row.amount());
        }
        return new SupplierPaymentReportResponse(rows,
                new SupplierPaymentReportResponse.Totals(money(total), rows.size()));
    }

    @Transactional(readOnly = true)
    public SupplierLedgerReportResponse supplierLedger(Long supplierId, LocalDate from, LocalDate to) {
        if (supplierId == null) {
            throw ApiException.badRequest("Select a supplier to view the ledger");
        }
        Long storeId = TenantContext.storeId();
        DateRange range = range(from, to);
        Supplier supplier = supplierRepository.findByIdAndStoreId(supplierId, storeId)
                .orElseThrow(() -> ApiException.notFound("Supplier not found"));
        List<Long> branchIds = branchScope(null);

        List<Purchase> bills = purchaseRepository
                .findSupplierBills(storeId, supplierId, branchIds, PurchaseStatus.COMPLETED)
                .stream()
                .filter(purchase -> accessible(purchase))
                .filter(purchase -> !purchase.getPurchaseDate().isAfter(range.to()))
                .toList();
        List<SupplierPayment> payments = paymentRepository
                .findByStoreIdAndSupplierIdAndPaymentDateLessThanEqualOrderByPaymentDateAscIdAsc(
                        storeId, supplierId, range.to());
        Map<Long, List<SupplierPaymentAllocation>> allocations = allocationsByPayment(payments);
        Map<Long, Purchase> purchases = purchasesById(
                allocations.values().stream().flatMap(List::stream).toList(), storeId);

        BigDecimal openingDebit = BigDecimal.ZERO;
        BigDecimal openingCredit = BigDecimal.ZERO;
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        List<SupplierLedgerReportResponse.Row> events = new ArrayList<>();

        for (Purchase bill : bills) {
            boolean before = bill.getPurchaseDate().isBefore(range.from());
            if (before) {
                openingDebit = openingDebit.add(money(bill.getGrandTotal()));
                continue;
            }
            events.add(new SupplierLedgerReportResponse.Row(bill.getPurchaseDate(), "PURCHASE",
                    bill.getPurchaseNumber(), "Purchase bill " + bill.getPurchaseNumber(),
                    money(bill.getGrandTotal()), BigDecimal.ZERO, BigDecimal.ZERO));
            totalDebit = totalDebit.add(money(bill.getGrandTotal()));
        }
        for (SupplierPayment payment : payments) {
            boolean before = payment.getPaymentDate().isBefore(range.from());
            for (SupplierPaymentAllocation allocation : allocations.getOrDefault(payment.getId(), List.of())) {
                Purchase bill = purchases.get(allocation.getPurchaseId());
                if (bill == null || !accessible(bill)) {
                    continue;
                }
                BigDecimal value = money(allocation.getAmount());
                if (before) {
                    openingCredit = openingCredit.add(value);
                    continue;
                }
                events.add(new SupplierLedgerReportResponse.Row(payment.getPaymentDate(), "PAYMENT",
                        payment.getPaymentNumber(),
                        "Payment via " + payment.getPaymentMethod().name()
                                + (bill.getPurchaseNumber() == null ? "" : " · bill " + bill.getPurchaseNumber()),
                        BigDecimal.ZERO, value, BigDecimal.ZERO));
                totalCredit = totalCredit.add(value);
            }
        }

        events.sort(Comparator.comparing(SupplierLedgerReportResponse.Row::date)
                .thenComparing(row -> row.type().equals("PURCHASE") ? 0 : 1)
                .thenComparing(SupplierLedgerReportResponse.Row::reference));
        BigDecimal balance = money(openingDebit.subtract(openingCredit));
        List<SupplierLedgerReportResponse.Row> rows = new ArrayList<>();
        for (SupplierLedgerReportResponse.Row event : events) {
            balance = money(balance.add(event.debit()).subtract(event.credit()));
            rows.add(new SupplierLedgerReportResponse.Row(event.date(), event.type(), event.reference(),
                    event.description(), money(event.debit()), money(event.credit()), balance));
        }
        return new SupplierLedgerReportResponse(supplier.getId(), supplierCode(supplier.getId()),
                supplier.getSupplierName(), money(openingDebit.subtract(openingCredit)), rows,
                money(totalDebit), money(totalCredit), balance);
    }

    // ---------- helpers ----------

    private PageResponse<SupplierPaymentResponse> mapPage(
            org.springframework.data.domain.Page<SupplierPayment> page) {
        Map<Long, Supplier> suppliers = supplierMap(TenantContext.storeId());
        Map<Long, List<SupplierPaymentAllocation>> allocations = allocationsByPayment(page.getContent());
        Map<Long, Purchase> purchases = purchasesById(
                allocations.values().stream().flatMap(List::stream).toList(), TenantContext.storeId());
        List<SupplierPaymentResponse> content = page.getContent().stream()
                .map(payment -> toResponse(payment, suppliers.get(payment.getSupplierId()),
                        allocations.getOrDefault(payment.getId(), List.of()), purchases))
                .toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    private List<SupplierPaymentResponse> toResponses(List<SupplierPayment> payments) {
        if (payments.isEmpty()) {
            return List.of();
        }
        Long storeId = TenantContext.storeId();
        Map<Long, Supplier> suppliers = supplierMap(storeId);
        Map<Long, List<SupplierPaymentAllocation>> allocations = allocationsByPayment(payments);
        Map<Long, Purchase> purchases = purchasesById(
                allocations.values().stream().flatMap(List::stream).toList(), storeId);
        return payments.stream()
                .map(payment -> toResponse(payment, suppliers.get(payment.getSupplierId()),
                        allocations.getOrDefault(payment.getId(), List.of()), purchases))
                .toList();
    }

    private SupplierPaymentResponse toResponse(SupplierPayment payment, Supplier supplier,
                                               List<SupplierPaymentAllocation> allocations,
                                               Map<Long, Purchase> purchases) {
        List<SupplierPaymentResponse.AllocationResponse> lines = allocations.stream()
                .map(allocation -> {
                    Purchase purchase = purchases.get(allocation.getPurchaseId());
                    return new SupplierPaymentResponse.AllocationResponse(allocation.getPurchaseId(),
                            purchase == null ? "" : purchase.getPurchaseNumber(),
                            purchase == null ? null : purchase.getPurchaseDate(),
                            money(allocation.getAmount()));
                })
                .toList();
        return new SupplierPaymentResponse(payment.getId(), payment.getPaymentNumber(),
                payment.getPaymentDate(), payment.getSupplierId(), supplierCode(payment.getSupplierId()),
                supplier == null ? "" : supplier.getSupplierName(), payment.getPaymentMethod(),
                money(payment.getAmount()), payment.getReferenceNumber(), payment.getRemarks(),
                payment.getCreatedBy(), payment.getCreatedByName(), payment.getCreatedAt(), lines);
    }

    private Map<Long, List<SupplierPaymentAllocation>> allocationsByPayment(List<SupplierPayment> payments) {
        if (payments.isEmpty()) {
            return Map.of();
        }
        return allocationRepository.findByPaymentIdIn(payments.stream().map(SupplierPayment::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(SupplierPaymentAllocation::getPaymentId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Long, Purchase> purchasesById(Collection<SupplierPaymentAllocation> allocations, Long storeId) {
        Set<Long> ids = allocations.stream().map(SupplierPaymentAllocation::getPurchaseId)
                .collect(Collectors.toCollection(HashSet::new));
        return purchasesById(ids, storeId);
    }

    private Map<Long, Purchase> purchasesById(Collection<Purchase> purchases) {
        return purchases.stream().collect(Collectors.toMap(Purchase::getId, Function.identity(),
                (a, b) -> a, LinkedHashMap::new));
    }

    private Map<Long, Purchase> purchasesById(Set<Long> ids, Long storeId) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return purchaseRepository.findAllById(ids).stream()
                .filter(purchase -> storeId.equals(purchase.getStoreId()))
                .collect(Collectors.toMap(Purchase::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    private List<SupplierAggregate> aggregates(Long storeId, List<Long> branchIds, Long supplierId,
                                               String search) {
        Map<Long, Supplier> suppliers = supplierMap(storeId);
        Map<Long, SupplierAggregate> aggregates = new LinkedHashMap<>();
        for (Purchase purchase : purchaseRepository.findForSettlement(storeId, branchIds,
                PurchaseStatus.COMPLETED)) {
            Supplier supplier = suppliers.get(purchase.getSupplierId());
            if (supplier == null) {
                continue;
            }
            if (supplierId != null && !supplierId.equals(supplier.getId())) {
                continue;
            }
            if (search != null && !supplier.getSupplierName().toLowerCase().contains(search)
                    && !supplierCode(supplier.getId()).toLowerCase().contains(search)) {
                continue;
            }
            SupplierAggregate aggregate = aggregates.computeIfAbsent(supplier.getId(),
                    id -> new SupplierAggregate(id, supplier.getSupplierName()));
            aggregate.totalPurchase = aggregate.totalPurchase.add(money(purchase.getGrandTotal()));
            aggregate.totalPaid = aggregate.totalPaid.add(money(purchase.getPaidAmount()));
            aggregate.billCount++;
            if (balanceOf(purchase).signum() > 0) {
                aggregate.pendingBillCount++;
            }
        }
        return aggregates.values().stream()
                .sorted(Comparator.comparing((SupplierAggregate aggregate) -> aggregate.outstanding()).reversed()
                        .thenComparing(aggregate -> aggregate.supplierName))
                .toList();
    }

    private boolean matchesBill(Purchase purchase, Supplier supplier, String search) {
        if (search == null) {
            return true;
        }
        return purchase.getPurchaseNumber().toLowerCase().contains(search)
                || (purchase.getInvoiceNumber() != null
                    && purchase.getInvoiceNumber().toLowerCase().contains(search))
                || (supplier != null && supplier.getSupplierName().toLowerCase().contains(search))
                || supplierCode(purchase.getSupplierId()).toLowerCase().contains(search);
    }

    private Map<Long, Supplier> supplierMap(Long storeId) {
        return supplierRepository.findByStoreIdOrderBySupplierName(storeId).stream()
                .collect(Collectors.toMap(Supplier::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    private Map<Long, Branch> branchMap(Long storeId) {
        return branchRepository.findByStoreIdOrderByBranchName(storeId).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    private String branchName(Map<Long, Branch> branches, Long branchId) {
        Branch branch = branches.get(branchId);
        return branch == null ? "" : branch.getBranchName();
    }

    private boolean accessible(Purchase purchase) {
        return TenantContext.user().canAccessBranch(purchase.getBranchId());
    }

    /**
     * null means every branch of the store; otherwise the caller's branch scope
     * is returned. An empty scope is replaced by an impossible id so that the
     * JPQL "in" clause stays valid.
     */
    private List<Long> branchScope(Long branchId) {
        AuthUser user = TenantContext.user();
        if (branchId != null) {
            if (!user.canAccessBranch(branchId)) {
                throw ApiException.forbidden("You do not have access to this branch");
            }
            return List.of(branchId);
        }
        if (user.allBranches()) {
            return null;
        }
        return user.branchIds().isEmpty() ? List.of(-1L) : List.copyOf(user.branchIds());
    }

    private SettlementStatus settlement(Purchase purchase) {
        BigDecimal grandTotal = money(purchase.getGrandTotal());
        BigDecimal paid = money(purchase.getPaidAmount());
        if (paid.signum() <= 0) {
            return grandTotal.signum() == 0 ? SettlementStatus.PAID : SettlementStatus.UNPAID;
        }
        return paid.compareTo(grandTotal) >= 0 ? SettlementStatus.PAID : SettlementStatus.PARTIALLY_PAID;
    }

    private BigDecimal balanceOf(Purchase purchase) {
        BigDecimal balance = money(purchase.getGrandTotal()).subtract(money(purchase.getPaidAmount()));
        return balance.signum() < 0 ? BigDecimal.ZERO.setScale(2) : balance;
    }

    private <T> PageResponse<T> page(List<T> rows, int page, int size) {
        int safeSize = Pagination.clampSize(size, Pagination.DEFAULT_MAX_SIZE);
        int safePage = Pagination.clampPage(page);
        int start = Math.min(safePage * safeSize, rows.size());
        int end = Math.min(start + safeSize, rows.size());
        int totalPages = rows.isEmpty() ? 0 : (int) Math.ceil(rows.size() / (double) safeSize);
        return new PageResponse<>(rows.subList(start, end), safePage, safeSize, rows.size(), totalPages);
    }

    private DateRange range(LocalDate from, LocalDate to) {
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (start.isAfter(end)) {
            throw ApiException.badRequest("From date cannot be after to date");
        }
        return new DateRange(start, end);
    }

    private String supplierCode(Long supplierId) {
        return "SUP-" + supplierId;
    }

    private String displayName(AuthUser user) {
        return user.fullName() == null || user.fullName().isBlank() ? user.username() : user.fullName();
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase();
    }

    // PostgreSQL cannot infer the type of a null varchar parameter used in lower(),
    // so the search query receives an empty sentinel instead of null.
    private String searchTerm(String value) {
        String cleaned = clean(value);
        return cleaned == null ? "" : cleaned;
    }

    private Page<SupplierPayment> searchPayments(Long storeId, Long supplierId, LocalDate from,
                                                 LocalDate to, List<Long> branchIds,
                                                 String search, Pageable pageable) {
        LocalDate start = from == null ? LocalDate.of(1900, 1, 1) : from;
        LocalDate end = to == null ? LocalDate.of(9999, 12, 31) : to;
        String term = searchTerm(search);
        return branchIds == null
                ? paymentRepository.searchAllBranches(storeId, supplierId, start, end, term, pageable)
                : paymentRepository.searchInBranches(storeId, supplierId, start, end, branchIds, term, pageable);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }

    private static final class SupplierAggregate {
        private final Long supplierId;
        private final String supplierName;
        private BigDecimal totalPurchase = BigDecimal.ZERO;
        private BigDecimal totalPaid = BigDecimal.ZERO;
        private int billCount;
        private int pendingBillCount;

        private SupplierAggregate(Long supplierId, String supplierName) {
            this.supplierId = supplierId;
            this.supplierName = supplierName;
        }

        private BigDecimal outstanding() {
            BigDecimal value = totalPurchase.subtract(totalPaid);
            return value.signum() < 0 ? BigDecimal.ZERO : value;
        }
    }
}
