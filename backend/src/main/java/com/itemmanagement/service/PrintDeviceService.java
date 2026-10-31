package com.itemmanagement.service;

import com.itemmanagement.dto.printing.DeviceConfigResponse;
import com.itemmanagement.dto.printing.DevicePairRequest;
import com.itemmanagement.dto.printing.DevicePairResponse;
import com.itemmanagement.dto.printing.PairingCodeResponse;
import com.itemmanagement.dto.printing.PrintDeviceResponse;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.PrintDevice;
import com.itemmanagement.entity.PrintPairingCode;
import com.itemmanagement.entity.Store;
import com.itemmanagement.entity.enums.PrintDevicePlatform;
import com.itemmanagement.entity.enums.PrintDeviceStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.PrintDeviceRepository;
import com.itemmanagement.repository.PrintPairingCodeRepository;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.repository.UserRepository;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PrintingTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PrintDeviceService {

    private static final Duration PAIRING_CODE_TTL = Duration.ofMinutes(10);

    private final PrintPairingCodeRepository pairingCodeRepository;
    private final PrintDeviceRepository deviceRepository;
    private final BranchRepository branchRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final PrinterService printerService;
    private final AuditService auditService;

    @Transactional
    public PairingCodeResponse createPairingCode(Long branchId) {
        Long storeId = TenantContext.storeId();
        TenantContext.requireBranch(branchId);
        Branch branch = branchRepository.findByIdAndStoreId(branchId, storeId)
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
        String code = PrintingTokens.newPairingCode();
        Instant expiresAt = Instant.now().plus(PAIRING_CODE_TTL);
        pairingCodeRepository.save(PrintPairingCode.builder()
                .storeId(storeId)
                .branchId(branch.getId())
                .codeHash(PrintingTokens.sha256(code))
                .createdBy(TenantContext.userId())
                .expiresAt(expiresAt)
                .build());
        auditService.log("PAIRING_CODE", "PRINT_DEVICE", null,
                "Generated pairing code for " + branch.getBranchName());
        return new PairingCodeResponse(code, branch.getId(), expiresAt);
    }

    /**
     * Called by a print bridge itself, so this endpoint has no JWT. The
     * single-use code generated above is the authorization proof.
     */
    @Transactional
    public DevicePairResponse pair(DevicePairRequest request) {
        String codeHash = PrintingTokens.sha256(PrintingTokens.normalizeCode(request.code()));
        PrintPairingCode pairing = pairingCodeRepository.findForUpdateByCodeHash(codeHash)
                .orElseThrow(() -> ApiException.badRequest("Pairing code is invalid or has expired"));
        if (pairing.getUsedAt() != null) {
            throw ApiException.badRequest("This pairing code has already been used. Generate a new code.");
        }
        if (!pairing.getExpiresAt().isAfter(Instant.now())) {
            throw ApiException.badRequest("Pairing code has expired. Generate a new code.");
        }
        PrintDevicePlatform platform = parsePlatform(request.platform());
        Branch branch = branchRepository.findByIdAndStoreId(pairing.getBranchId(), pairing.getStoreId())
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
        Store store = storeRepository.findById(pairing.getStoreId())
                .orElseThrow(() -> ApiException.notFound("Store not found"));
        String username = pairing.getCreatedBy() == null ? null
                : userRepository.findById(pairing.getCreatedBy()).map(user -> user.getUsername()).orElse(null);

        String token = PrintingTokens.newDeviceToken();
        PrintDevice device = deviceRepository.save(PrintDevice.builder()
                .storeId(store.getId())
                .branchId(branch.getId())
                .deviceName(request.deviceName().trim())
                .platform(platform)
                .tokenHash(PrintingTokens.sha256(token))
                .status(PrintDeviceStatus.ACTIVE)
                .pairedBy(pairing.getCreatedBy())
                .pairedByUsername(username)
                .build());
        pairing.setUsedAt(Instant.now());
        pairingCodeRepository.save(pairing);

        auditService.logFor(store.getId(), pairing.getCreatedBy(), username,
                "PAIR", "PRINT_DEVICE", device.getId(),
                "Paired " + platform + " device " + device.getDeviceName() + " for " + branch.getBranchName());

        return new DevicePairResponse(
                device.getId(),
                token,
                device.getDeviceName(),
                device.getPlatform().name(),
                store.getId(),
                store.getStoreName(),
                branch.getId(),
                branch.getBranchName(),
                printerService.listForBranch(store.getId(), branch.getId())
        );
    }

    /**
     * Bridge authentication: the opaque token is verified server-side on
     * every configuration pull, so a revoked bridge loses access at once.
     */
    @Transactional
    public DeviceConfigResponse config(String deviceToken) {
        if (deviceToken == null || deviceToken.isBlank()) {
            throw ApiException.unauthorized("Print bridge token is required");
        }
        PrintDevice device = deviceRepository.findByTokenHash(PrintingTokens.sha256(deviceToken.trim()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid print bridge token"));
        if (device.getStatus() != PrintDeviceStatus.ACTIVE) {
            throw ApiException.unauthorized("This print bridge has been revoked. Pair it again from store settings.");
        }
        device.setLastSeenAt(Instant.now());
        deviceRepository.save(device);
        Store store = storeRepository.findById(device.getStoreId())
                .orElseThrow(() -> ApiException.notFound("Store not found"));
        Branch branch = branchRepository.findByIdAndStoreId(device.getBranchId(), device.getStoreId())
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
        return new DeviceConfigResponse(
                device.getId(),
                device.getDeviceName(),
                device.getPlatform().name(),
                store.getId(),
                store.getStoreName(),
                branch.getId(),
                branch.getBranchName(),
                printerService.listForBranch(store.getId(), branch.getId()),
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public List<PrintDeviceResponse> list(Long branchId) {
        Long storeId = TenantContext.storeId();
        TenantContext.requireBranch(branchId);
        branchRepository.findByIdAndStoreId(branchId, storeId)
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
        return deviceRepository.findByStoreIdAndBranchIdOrderByCreatedAtDesc(storeId, branchId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public PrintDeviceResponse revoke(Long id) {
        Long storeId = TenantContext.storeId();
        PrintDevice device = deviceRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Print bridge device not found"));
        TenantContext.requireBranch(device.getBranchId());
        device.setStatus(PrintDeviceStatus.REVOKED);
        deviceRepository.save(device);
        auditService.log("REVOKE", "PRINT_DEVICE", device.getId(),
                "Revoked print bridge " + device.getDeviceName());
        return toResponse(device);
    }

    private PrintDeviceResponse toResponse(PrintDevice device) {
        return new PrintDeviceResponse(
                device.getId(),
                device.getDeviceName(),
                device.getPlatform().name(),
                device.getStatus().name(),
                device.getPairedByUsername(),
                device.getCreatedAt(),
                device.getLastSeenAt()
        );
    }

    private PrintDevicePlatform parsePlatform(String value) {
        try {
            return PrintDevicePlatform.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("Platform must be ANDROID or WINDOWS");
        }
    }
}
