package com.itemmanagement.controller;

import com.itemmanagement.dto.transaction.TransferRequest;
import com.itemmanagement.dto.transaction.TransferResponse;
import com.itemmanagement.entity.enums.TransferStatus;
import com.itemmanagement.service.TransferService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @GetMapping
    @PreAuthorize("hasAuthority('TRANSFER_VIEW')")
    public PageResponse<TransferResponse> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) TransferStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return transferService.list(branchId, status, from, to, search, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TRANSFER_VIEW')")
    public TransferResponse get(@PathVariable Long id) {
        return transferService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('TRANSFER_MANAGE')")
    public TransferResponse create(@Valid @RequestBody TransferRequest request) {
        return transferService.create(request);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('TRANSFER_APPROVE')")
    public TransferResponse approve(@PathVariable Long id) {
        return transferService.approve(id);
    }

    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAuthority('TRANSFER_MANAGE')")
    public TransferResponse dispatch(@PathVariable Long id) {
        return transferService.dispatch(id);
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('TRANSFER_MANAGE')")
    public TransferResponse receive(@PathVariable Long id) {
        return transferService.receive(id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('TRANSFER_APPROVE')")
    public TransferResponse reject(@PathVariable Long id) {
        return transferService.reject(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('TRANSFER_MANAGE')")
    public TransferResponse cancel(@PathVariable Long id) {
        return transferService.cancel(id);
    }
}
