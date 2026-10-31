package com.itemmanagement.service;

import com.itemmanagement.dto.printing.PrintJobRequest;
import com.itemmanagement.dto.printing.PrintJobResponse;
import com.itemmanagement.dto.printing.PrintJobStatusRequest;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.PrintJob;
import com.itemmanagement.entity.PrinterConfig;
import com.itemmanagement.entity.Sale;
import com.itemmanagement.entity.enums.PrintDocumentType;
import com.itemmanagement.entity.enums.PrintJobChannel;
import com.itemmanagement.entity.enums.PrintJobStatus;
import com.itemmanagement.entity.enums.PrintJobTrigger;
import com.itemmanagement.entity.enums.PrinterPurpose;
import com.itemmanagement.entity.enums.SaleStatus;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.PrintJobRepository;
import com.itemmanagement.repository.SaleRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PrintJobService {

    private final PrintJobRepository jobRepository;
    private final PrintJobWriter jobWriter;
    private final SaleRepository saleRepository;
    private final BranchRepository branchRepository;
    private final PrinterService printerService;

    @Transactional
    public PrintJobResponse create(PrintJobRequest request) {
        Long storeId = TenantContext.storeId();
        AuthUser user = TenantContext.user();
        PrintDocumentType documentType = parseDocumentType(request.documentType());
        PrintJobChannel channel = parseChannel(request.channel());
        PrintJobTrigger trigger = parseTrigger(request.trigger());

        if (documentType != PrintDocumentType.SALE_RECEIPT) {
            throw ApiException.badRequest("Thermal printing is currently available for sale receipts only");
        }
        if (request.documentId() == null) {
            throw ApiException.badRequest("Sale is required for a receipt print job");
        }
        Sale sale = saleRepository.findByIdAndStoreId(request.documentId(), storeId)
                .orElseThrow(() -> ApiException.notFound("Sale not found"));
        if (sale.getStatus() != SaleStatus.COMPLETED) {
            throw ApiException.badRequest("Only completed sales can be printed");
        }
        TenantContext.requireBranch(sale.getBranchId());

        String jobKey = trigger == PrintJobTrigger.AUTO
                ? "AUTO:" + documentType + ":" + channel + ":" + sale.getBranchId() + ":" + sale.getId()
                : "MANUAL:" + UUID.randomUUID();
        if (trigger == PrintJobTrigger.AUTO) {
            PrintJob existing = jobRepository.findByStoreIdAndJobKey(storeId, jobKey).orElse(null);
            if (existing != null) {
                return toResponse(existing, true);
            }
        }

        PrinterConfig printer = resolvePrinter(storeId, sale.getBranchId(), channel,
                request.printerConfigId(), true);
        PrintJob job = PrintJob.builder()
                .storeId(storeId)
                .branchId(sale.getBranchId())
                .jobKey(jobKey)
                .documentType(documentType)
                .documentId(sale.getId())
                .documentNumber(sale.getSalesNumber())
                .printerConfigId(printer == null ? null : printer.getId())
                .printerName(printer == null ? null : printer.getPrinterName())
                .channel(channel)
                .triggerType(trigger)
                .status(PrintJobStatus.PENDING)
                .attempts(0)
                .createdBy(user.userId())
                .createdByUsername(user.username())
                .build();
        try {
            jobWriter.insert(job);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent automatic requests for the same receipt: return the
            // job that won instead of creating a second print.
            PrintJob existing = jobRepository.findByStoreIdAndJobKey(storeId, jobKey)
                    .orElseThrow(() -> ex);
            return toResponse(existing, true);
        }
        return toResponse(job, false);
    }

    @Transactional
    public PrintJobResponse updateStatus(Long id, PrintJobStatusRequest request) {
        PrintJob job = find(id);
        TenantContext.requireBranch(job.getBranchId());
        PrintJobStatus status = parseFinalStatus(request.status());
        if (job.getStatus() != PrintJobStatus.PENDING) {
            throw ApiException.conflict("This print job has already been finalised");
        }
        job.setStatus(status);
        job.setAttempts(job.getAttempts() + 1);
        if (status == PrintJobStatus.PRINTED) {
            job.setPrintedAt(Instant.now());
            job.setErrorMessage(null);
        } else {
            String message = request.errorMessage() == null || request.errorMessage().isBlank()
                    ? "The print bridge reported a failure" : request.errorMessage().trim();
            job.setErrorMessage(message.length() > 500 ? message.substring(0, 500) : message);
        }
        jobRepository.save(job);
        return toResponse(job, false);
    }

    @Transactional(readOnly = true)
    public PageResponse<PrintJobResponse> history(int page, int size, String statusFilter, Long branchId) {
        Long storeId = TenantContext.storeId();
        AuthUser user = TenantContext.user();
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        PrintJobStatus status = statusFilter == null || statusFilter.isBlank()
                ? null : parseStatusFilter(statusFilter);

        if (branchId != null) {
            TenantContext.requireBranch(branchId);
            branchRepository.findByIdAndStoreId(branchId, storeId)
                    .orElseThrow(() -> ApiException.notFound("Branch not found"));
        }

        if (!user.allBranches() && user.branchIds().isEmpty()) {
            return new PageResponse<>(List.of(), safePage, safeSize, 0, 0);
        }

        Page<PrintJob> result;
        if (branchId != null) {
            result = status == null
                    ? jobRepository.findByStoreIdAndBranchIdOrderByCreatedAtDesc(storeId, branchId, pageable)
                    : jobRepository.findByStoreIdAndBranchIdAndStatusOrderByCreatedAtDesc(
                            storeId, branchId, status, pageable);
        } else if (user.allBranches()) {
            result = status == null
                    ? jobRepository.findByStoreIdOrderByCreatedAtDesc(storeId, pageable)
                    : jobRepository.findByStoreIdAndStatusOrderByCreatedAtDesc(storeId, status, pageable);
        } else {
            result = status == null
                    ? jobRepository.findByStoreIdAndBranchIdInOrderByCreatedAtDesc(
                            storeId, user.branchIds(), pageable)
                    : jobRepository.findByStoreIdAndBranchIdInAndStatusOrderByCreatedAtDesc(
                            storeId, user.branchIds(), status, pageable);
        }
        return PageResponse.of(result, job -> toResponse(job, false));
    }

    @Transactional(readOnly = true)
    public PrintJobResponse detail(Long id) {
        PrintJob job = find(id);
        TenantContext.requireBranch(job.getBranchId());
        return toResponse(job, false);
    }

    @Transactional
    public PrintJobResponse reprint(Long id) {
        PrintJob source = find(id);
        TenantContext.requireBranch(source.getBranchId());
        if (source.getDocumentId() == null || source.getDocumentType() != PrintDocumentType.SALE_RECEIPT) {
            throw ApiException.badRequest("This print job cannot be reprinted");
        }
        return create(new PrintJobRequest(
                source.getDocumentType().name(),
                source.getDocumentId(),
                source.getChannel().name(),
                PrintJobTrigger.MANUAL.name(),
                source.getPrinterConfigId()
        ));
    }

    private PrintJob find(Long id) {
        Long storeId = TenantContext.storeId();
        return jobRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Print job not found"));
    }

    /**
     * Android jobs always need an active configured printer. Windows jobs use
     * the PC's default installed printer, so a config is optional there.
     */
    private PrinterConfig resolvePrinter(Long storeId, Long branchId, PrintJobChannel channel,
                                         Long printerConfigId, boolean requireForAndroid) {
        if (printerConfigId != null) {
            PrinterConfig config = printerService.findForStore(printerConfigId, storeId);
            if (!config.getBranchId().equals(branchId)) {
                throw ApiException.notFound("Printer not found for this branch");
            }
            if (config.getStatus() != StoreStatus.ACTIVE) {
                throw ApiException.badRequest("The selected printer is inactive");
            }
            if (config.getPurpose() != PrinterPurpose.RECEIPT && config.getPurpose() != PrinterPurpose.ALL) {
                throw ApiException.badRequest("The selected printer is not assigned to receipts");
            }
            return config;
        }
        PrinterConfig resolved = printerService.resolveForPrinting(storeId, branchId, PrinterPurpose.RECEIPT);
        if (resolved == null && channel == PrintJobChannel.ANDROID && requireForAndroid) {
            throw ApiException.badRequest(
                    "No active receipt printer is configured for this branch. "
                            + "Ask the store owner to add one in Settings → Printers.");
        }
        return resolved;
    }

    private PrintJobResponse toResponse(PrintJob job, boolean duplicate) {
        Branch branch = branchRepository.findByIdAndStoreId(job.getBranchId(), job.getStoreId()).orElse(null);
        PrinterConfig printer = job.getPrinterConfigId() == null ? null
                : printerService.findForStore(job.getPrinterConfigId(), job.getStoreId());
        PrinterConfig effective = printer;
        return new PrintJobResponse(
                job.getId(),
                job.getJobKey(),
                job.getDocumentType().name(),
                job.getDocumentId(),
                job.getDocumentNumber(),
                job.getBranchId(),
                branch == null ? null : branch.getBranchName(),
                job.getPrinterConfigId(),
                job.getPrinterName(),
                effective == null ? null : effective.getIpAddress(),
                effective == null ? null : effective.getPort(),
                effective == null ? null : effective.getPaperWidth().name(),
                effective == null ? null : effective.getPurpose().name(),
                job.getChannel().name(),
                job.getTriggerType().name(),
                job.getStatus().name(),
                job.getAttempts(),
                job.getErrorMessage(),
                job.getCreatedByUsername(),
                job.getCreatedAt(),
                job.getPrintedAt(),
                duplicate
        );
    }

    private PrintDocumentType parseDocumentType(String value) {
        try {
            return PrintDocumentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("Unknown document type");
        }
    }

    private PrintJobChannel parseChannel(String value) {
        try {
            return PrintJobChannel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("Print channel must be WINDOWS or ANDROID");
        }
    }

    private PrintJobTrigger parseTrigger(String value) {
        try {
            return PrintJobTrigger.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("Print trigger must be AUTO or MANUAL");
        }
    }

    private PrintJobStatus parseFinalStatus(String value) {
        PrintJobStatus status;
        try {
            status = PrintJobStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("Print status must be PRINTED or FAILED");
        }
        if (status == PrintJobStatus.PENDING) {
            throw ApiException.badRequest("Print status must be PRINTED or FAILED");
        }
        return status;
    }

    private PrintJobStatus parseStatusFilter(String value) {
        try {
            return PrintJobStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("Unknown print status filter");
        }
    }
}
