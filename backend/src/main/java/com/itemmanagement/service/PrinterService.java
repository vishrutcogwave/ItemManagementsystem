package com.itemmanagement.service;

import com.itemmanagement.dto.printing.PrinterConfigRequest;
import com.itemmanagement.dto.printing.PrinterConfigResponse;
import com.itemmanagement.dto.printing.PrinterTestResultRequest;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.PrinterConfig;
import com.itemmanagement.entity.enums.PaperWidth;
import com.itemmanagement.entity.enums.PrinterPurpose;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.PrinterConfigRepository;
import com.itemmanagement.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PrinterService {

    private static final Set<PrinterPurpose> RECEIPT_PURPOSES =
            Set.of(PrinterPurpose.RECEIPT, PrinterPurpose.ALL);

    private final PrinterConfigRepository printerRepository;
    private final BranchRepository branchRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<PrinterConfigResponse> list(Long branchId) {
        Branch branch = requireBranch(branchId);
        return printerRepository
                .findByStoreIdAndBranchIdOrderByPrinterNameAsc(branch.getStoreId(), branch.getId())
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public PrinterConfigResponse create(PrinterConfigRequest request) {
        Branch branch = requireBranch(request.branchId());
        Long storeId = branch.getStoreId();
        String name = cleanName(request.printerName());
        if (printerRepository.existsByStoreIdAndBranchIdAndPrinterNameIgnoreCase(
                storeId, branch.getId(), name)) {
            throw ApiException.conflict("A printer named '" + name + "' already exists for this branch");
        }
        String ipAddress = validateAddress(request.ipAddress());
        PrinterConfig config = PrinterConfig.builder()
                .storeId(storeId)
                .branchId(branch.getId())
                .printerName(name)
                .ipAddress(ipAddress)
                .port(request.port())
                .paperWidth(parsePaperWidth(request.paperWidth()))
                .purpose(parsePurpose(request.purpose()))
                .status(parseStatus(request.status(), StoreStatus.ACTIVE))
                .build();
        printerRepository.save(config);
        auditService.log("CREATE", "PRINTER_CONFIG", config.getId(),
                "Added printer " + name + " (" + ipAddress + ":" + config.getPort()
                        + ") for " + branch.getBranchName());
        return toResponse(config);
    }

    @Transactional
    public PrinterConfigResponse update(Long id, PrinterConfigRequest request) {
        PrinterConfig config = find(id);
        Branch branch = requireBranch(request.branchId());
        if (!branch.getStoreId().equals(config.getStoreId())) {
            throw ApiException.notFound("Printer not found");
        }
        String name = cleanName(request.printerName());
        if (printerRepository.existsByStoreIdAndBranchIdAndPrinterNameIgnoreCaseAndIdNot(
                config.getStoreId(), branch.getId(), name, config.getId())) {
            throw ApiException.conflict("A printer named '" + name + "' already exists for this branch");
        }
        config.setBranchId(branch.getId());
        config.setPrinterName(name);
        config.setIpAddress(validateAddress(request.ipAddress()));
        config.setPort(request.port());
        config.setPaperWidth(parsePaperWidth(request.paperWidth()));
        config.setPurpose(parsePurpose(request.purpose()));
        if (request.status() != null && !request.status().isBlank()) {
            config.setStatus(parseStatus(request.status(), config.getStatus()));
        }
        printerRepository.save(config);
        auditService.log("UPDATE", "PRINTER_CONFIG", config.getId(),
                "Updated printer " + name + " for " + branch.getBranchName());
        return toResponse(config);
    }

    @Transactional
    public PrinterConfigResponse updateStatus(Long id, String status) {
        PrinterConfig config = find(id);
        requireBranch(config.getBranchId());
        config.setStatus(parseStatus(status, config.getStatus()));
        printerRepository.save(config);
        auditService.log("STATUS", "PRINTER_CONFIG", config.getId(),
                "Printer " + config.getPrinterName() + " set to " + config.getStatus());
        return toResponse(config);
    }

    @Transactional
    public PrinterConfigResponse recordTest(Long id, PrinterTestResultRequest request) {
        PrinterConfig config = find(id);
        requireBranch(config.getBranchId());
        String message = request.message() == null || request.message().isBlank()
                ? (Boolean.TRUE.equals(request.success()) ? "Connected successfully" : "Connection failed")
                : request.message().trim();
        config.setLastTestAt(Instant.now());
        config.setLastTestMessage(message.length() > 255 ? message.substring(0, 255) : message);
        printerRepository.save(config);
        return toResponse(config);
    }

    @Transactional
    public void delete(Long id) {
        PrinterConfig config = find(id);
        requireBranch(config.getBranchId());
        printerRepository.delete(config);
        auditService.log("DELETE", "PRINTER_CONFIG", id,
                "Removed printer " + config.getPrinterName());
    }

    @Transactional(readOnly = true)
    public List<PrinterConfigResponse> listForBranch(Long storeId, Long branchId) {
        return printerRepository.findByStoreIdAndBranchIdOrderByPrinterNameAsc(storeId, branchId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PrinterConfig resolveForPrinting(Long storeId, Long branchId, PrinterPurpose purpose) {
        return printerRepository
                .findFirstByStoreIdAndBranchIdAndStatusAndPurposeInOrderByIdAsc(
                        storeId, branchId, StoreStatus.ACTIVE, Set.of(purpose, PrinterPurpose.ALL))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public PrinterConfig findForStore(Long id, Long storeId) {
        return printerRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Printer not found"));
    }

    public PrinterConfigResponse toResponse(PrinterConfig config) {
        return new PrinterConfigResponse(
                config.getId(),
                config.getStoreId(),
                config.getBranchId(),
                config.getPrinterName(),
                config.getIpAddress(),
                config.getPort(),
                config.getPaperWidth().name(),
                config.getPurpose().name(),
                config.getStatus().name(),
                config.getLastTestAt(),
                config.getLastTestMessage(),
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }

    private PrinterConfig find(Long id) {
        Long storeId = TenantContext.storeId();
        return printerRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Printer not found"));
    }

    private Branch requireBranch(Long branchId) {
        Long storeId = TenantContext.storeId();
        TenantContext.requireBranch(branchId);
        return branchRepository.findByIdAndStoreId(branchId, storeId)
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
    }

    private String cleanName(String name) {
        return name == null ? "" : name.trim();
    }

    private String validateAddress(String value) {
        String address = value == null ? "" : value.trim();
        if (address.equals("0.0.0.0") || address.equals("255.255.255.255")) {
            throw ApiException.badRequest("Enter a usable printer IP address");
        }
        if (address.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            int first = Integer.parseInt(address.substring(0, address.indexOf('.')));
            if (first >= 224) {
                throw ApiException.badRequest("Enter a unicast printer IP address, not a multicast or reserved address");
            }
        }
        return address;
    }

    private PaperWidth parsePaperWidth(String value) {
        try {
            return PaperWidth.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("Paper width must be MM58 or MM80");
        }
    }

    private PrinterPurpose parsePurpose(String value) {
        try {
            return PrinterPurpose.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("Printer purpose must be RECEIPT, PURCHASE, REPORT or ALL");
        }
    }

    private StoreStatus parseStatus(String value, StoreStatus fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return StoreStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("Printer status must be ACTIVE or INACTIVE");
        }
    }
}
