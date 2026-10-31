package com.itemmanagement.controller;

import com.itemmanagement.dto.common.StatusRequest;
import com.itemmanagement.dto.printing.PairingCodeResponse;
import com.itemmanagement.dto.printing.PrintDeviceResponse;
import com.itemmanagement.dto.printing.PrinterConfigRequest;
import com.itemmanagement.dto.printing.PrinterConfigResponse;
import com.itemmanagement.dto.printing.PrinterTestResultRequest;
import com.itemmanagement.service.PrintDeviceService;
import com.itemmanagement.service.PrinterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/printing/printers")
@RequiredArgsConstructor
public class PrinterController {

    private final PrinterService printerService;
    private final PrintDeviceService deviceService;

    @GetMapping
    @PreAuthorize("hasAuthority('PRINTER_VIEW')")
    public List<PrinterConfigResponse> list(@RequestParam Long branchId) {
        return printerService.list(branchId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PRINTER_MANAGE')")
    public PrinterConfigResponse create(@Valid @RequestBody PrinterConfigRequest request) {
        return printerService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PRINTER_MANAGE')")
    public PrinterConfigResponse update(@PathVariable Long id,
                                        @Valid @RequestBody PrinterConfigRequest request) {
        return printerService.update(id, request);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PRINTER_MANAGE')")
    public PrinterConfigResponse updateStatus(@PathVariable Long id,
                                              @Valid @RequestBody StatusRequest request) {
        return printerService.updateStatus(id, request.status().name());
    }

    @PutMapping("/{id}/test-result")
    @PreAuthorize("hasAuthority('PRINTER_MANAGE')")
    public PrinterConfigResponse recordTest(@PathVariable Long id,
                                            @Valid @RequestBody PrinterTestResultRequest request) {
        return printerService.recordTest(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PRINTER_MANAGE')")
    public void delete(@PathVariable Long id) {
        printerService.delete(id);
    }

    @PostMapping("/pairing-code")
    @PreAuthorize("hasAuthority('PRINTER_MANAGE')")
    public PairingCodeResponse pairingCode(@RequestParam Long branchId) {
        return deviceService.createPairingCode(branchId);
    }

    @GetMapping("/devices")
    @PreAuthorize("hasAuthority('PRINTER_MANAGE')")
    public List<PrintDeviceResponse> devices(@RequestParam Long branchId) {
        return deviceService.list(branchId);
    }

    @DeleteMapping("/devices/{id}")
    @PreAuthorize("hasAuthority('PRINTER_MANAGE')")
    public PrintDeviceResponse revokeDevice(@PathVariable Long id) {
        return deviceService.revoke(id);
    }
}
