package com.itemmanagement.controller;

import com.itemmanagement.dto.report.BranchReportResponse;
import com.itemmanagement.dto.report.GstReportResponse;
import com.itemmanagement.dto.report.LowStockReportResponse;
import com.itemmanagement.dto.report.ProfitReportResponse;
import com.itemmanagement.dto.report.PurchaseReportResponse;
import com.itemmanagement.dto.report.SalesReportResponse;
import com.itemmanagement.dto.report.StockReportResponse;
import com.itemmanagement.dto.report.StoreReportResponse;
import com.itemmanagement.dto.report.SupplierBillReportResponse;
import com.itemmanagement.dto.report.SupplierLedgerReportResponse;
import com.itemmanagement.dto.report.SupplierOutstandingReportResponse;
import com.itemmanagement.dto.report.SupplierPaymentReportResponse;
import com.itemmanagement.service.ReportService;
import com.itemmanagement.service.SupplierPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final SupplierPaymentService supplierPaymentService;

    @GetMapping("/purchases")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public PurchaseReportResponse purchases(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long itemId,
            @RequestParam(required = false) String search) {
        return reportService.purchaseReport(from, to, branchId, categoryId, itemId, search);
    }

    @GetMapping("/sales")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public SalesReportResponse sales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long itemId,
            @RequestParam(required = false) String search) {
        return reportService.salesReport(from, to, branchId, categoryId, itemId, search);
    }

    @GetMapping("/gst")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public GstReportResponse gst(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) BigDecimal taxRate) {
        return reportService.gstReport(from, to, branchId, taxRate);
    }

    @GetMapping("/stock")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public StockReportResponse stock(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long itemId,
            @RequestParam(required = false) String search) {
        return reportService.stockReport(from, to, branchId, categoryId, itemId, search);
    }

    @GetMapping("/low-stock")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public LowStockReportResponse lowStock(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String search) {
        return reportService.lowStockReport(branchId, categoryId, search);
    }

    @GetMapping("/branches")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public BranchReportResponse branches(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId) {
        return reportService.branchReport(from, to, branchId);
    }

    @GetMapping("/store")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public StoreReportResponse store(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId) {
        return reportService.storeReport(from, to, branchId);
    }

    @GetMapping("/profit")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public ProfitReportResponse profit(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long itemId,
            @RequestParam(required = false) String search) {
        return reportService.profitReport(from, to, branchId, categoryId, itemId, search);
    }

    @GetMapping("/supplier-outstanding")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public SupplierOutstandingReportResponse supplierOutstanding(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String search) {
        return supplierPaymentService.supplierOutstandingReport(supplierId, search, branchId);
    }

    @GetMapping("/supplier-bills")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public SupplierBillReportResponse supplierBills(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String settlement,
            @RequestParam(required = false) String search) {
        return supplierPaymentService.supplierBillsReport(supplierId, from, to, branchId, settlement, search);
    }

    @GetMapping("/supplier-payments")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public SupplierPaymentReportResponse supplierPayments(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String search) {
        return supplierPaymentService.supplierPaymentsReport(from, to, supplierId, branchId, search);
    }

    @GetMapping("/supplier-ledger")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public SupplierLedgerReportResponse supplierLedger(
            @RequestParam Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return supplierPaymentService.supplierLedger(supplierId, from, to);
    }
}
