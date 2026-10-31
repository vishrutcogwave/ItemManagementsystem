package com.itemmanagement.controller;

import com.itemmanagement.dto.printing.PrintJobRequest;
import com.itemmanagement.dto.printing.PrintJobResponse;
import com.itemmanagement.dto.printing.PrintJobStatusRequest;
import com.itemmanagement.service.PrintJobService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/printing/jobs")
@RequiredArgsConstructor
public class PrintJobController {

    private final PrintJobService printJobService;

    @PostMapping
    @PreAuthorize("hasAuthority('SALES_MANAGE')")
    public PrintJobResponse create(@Valid @RequestBody PrintJobRequest request) {
        return printJobService.create(request);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('SALES_MANAGE')")
    public PrintJobResponse updateStatus(@PathVariable Long id,
                                         @Valid @RequestBody PrintJobStatusRequest request) {
        return printJobService.updateStatus(id, request);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    public PageResponse<PrintJobResponse> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long branchId) {
        return printJobService.history(page, size, status, branchId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    public PrintJobResponse detail(@PathVariable Long id) {
        return printJobService.detail(id);
    }

    @PostMapping("/{id}/reprint")
    @PreAuthorize("hasAuthority('SALES_MANAGE')")
    public PrintJobResponse reprint(@PathVariable Long id) {
        return printJobService.reprint(id);
    }
}
