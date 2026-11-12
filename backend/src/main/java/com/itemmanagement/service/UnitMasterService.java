package com.itemmanagement.service;

import com.itemmanagement.dto.master.UnitMasterRequest;
import com.itemmanagement.dto.master.UnitMasterResponse;
import com.itemmanagement.entity.UnitMaster;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.ItemRepository;
import com.itemmanagement.repository.PurchaseDetailRepository;
import com.itemmanagement.repository.SalesDetailRepository;
import com.itemmanagement.repository.StockAdjustmentRepository;
import com.itemmanagement.repository.StockTransferDetailRepository;
import com.itemmanagement.repository.UnitMasterRepository;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PageResponse;
import com.itemmanagement.util.Pagination;
import com.itemmanagement.util.UnitConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UnitMasterService {

    private final UnitMasterRepository unitMasterRepository;
    private final ItemRepository itemRepository;
    private final PurchaseDetailRepository purchaseDetailRepository;
    private final SalesDetailRepository salesDetailRepository;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final StockTransferDetailRepository stockTransferDetailRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<UnitMasterResponse> list(String search, StoreStatus status, int page, int size) {
        Long storeId = TenantContext.storeId();
        Page<UnitMaster> units = unitMasterRepository.search(storeId, clean(search), status,
                Pagination.of(page, size));
        return PageResponse.of(units, unit -> toResponse(storeId, unit));
    }

    @Transactional(readOnly = true)
    public List<UnitMasterResponse> options() {
        Long storeId = TenantContext.storeId();
        return unitMasterRepository.findByStoreIdOrderByUnitCodeAsc(storeId).stream()
                .map(unit -> toResponse(storeId, unit)).toList();
    }

    @Transactional(readOnly = true)
    public UnitMasterResponse get(Long id) {
        Long storeId = TenantContext.storeId();
        return toResponse(storeId, find(id, storeId));
    }

    @Transactional
    public UnitMasterResponse create(UnitMasterRequest request) {
        Long storeId = TenantContext.storeId();
        String code = UnitConverter.normalize(request.unitCode());
        if (unitMasterRepository.existsByStoreIdAndUnitCodeIgnoreCase(storeId, code)) {
            throw ApiException.conflict("A unit with this code already exists");
        }
        UnitMaster unit = new UnitMaster();
        unit.setStoreId(storeId);
        unit.setStatus(request.status() == null ? StoreStatus.ACTIVE : request.status());
        apply(unit, request, code);
        unitMasterRepository.save(unit);
        auditService.log("CREATE", "UNIT", unit.getId(), "Created unit " + code);
        return toResponse(storeId, unit);
    }

    @Transactional
    public UnitMasterResponse update(Long id, UnitMasterRequest request) {
        Long storeId = TenantContext.storeId();
        UnitMaster unit = find(id, storeId);
        String code = UnitConverter.normalize(request.unitCode());
        if (!unit.getUnitCode().equalsIgnoreCase(code)) {
            if (unitMasterRepository.existsByStoreIdAndUnitCodeIgnoreCase(storeId, code)) {
                throw ApiException.conflict("A unit with this code already exists");
            }
            if (usage(storeId, unit.getUnitCode()) > 0) {
                throw ApiException.conflict("This unit is used by existing items or transactions. "
                        + "Deactivate it instead of changing its code.");
            }
        }
        apply(unit, request, code);
        unitMasterRepository.save(unit);
        auditService.log("UPDATE", "UNIT", id, "Updated unit " + code);
        return toResponse(storeId, unit);
    }

    @Transactional
    public UnitMasterResponse updateStatus(Long id, StoreStatus status) {
        Long storeId = TenantContext.storeId();
        UnitMaster unit = find(id, storeId);
        unit.setStatus(status);
        unitMasterRepository.save(unit);
        auditService.log(status == StoreStatus.ACTIVE ? "ACTIVATE" : "DEACTIVATE", "UNIT", id,
                "Unit status changed to " + status);
        return toResponse(storeId, unit);
    }

    @Transactional
    public void delete(Long id) {
        Long storeId = TenantContext.storeId();
        UnitMaster unit = find(id, storeId);
        long items = itemRepository.countByStoreIdAndBaseUnitIgnoreCase(storeId, unit.getUnitCode());
        long purchases = purchaseDetailRepository.countByStoreIdAndUnitIgnoreCase(storeId, unit.getUnitCode());
        long sales = salesDetailRepository.countByStoreIdAndUnitIgnoreCase(storeId, unit.getUnitCode());
        long adjustments = stockAdjustmentRepository.countByStoreIdAndUnitIgnoreCase(storeId, unit.getUnitCode());
        long transfers = stockTransferDetailRepository.countByStoreIdAndUnitIgnoreCase(storeId, unit.getUnitCode());
        if (items > 0 || purchases > 0 || sales > 0 || adjustments > 0 || transfers > 0) {
            throw ApiException.badRequest("This unit is used by " + items + " item(s), "
                    + purchases + " purchase line(s), " + sales + " sales line(s), "
                    + adjustments + " stock adjustment(s) and " + transfers
                    + " transfer line(s). Deactivate it instead of deleting.");
        }
        unitMasterRepository.delete(unit);
        auditService.log("DELETE", "UNIT", id, "Deleted unit " + unit.getUnitCode());
    }

    /**
     * Resolves a unit code or unit name typed by a user to the canonical stored
     * code for this store. Returns null when the value is blank or unknown.
     */
    @Transactional(readOnly = true)
    public String resolve(Long storeId, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String code = UnitConverter.normalize(value);
        return unitMasterRepository.findByStoreIdAndUnitCodeIgnoreCase(storeId, code)
                .or(() -> unitMasterRepository.findFirstByStoreIdAndUnitNameIgnoreCase(storeId, value.trim()))
                .map(UnitMaster::getUnitCode)
                .orElse(null);
    }

    /**
     * Validates a unit selected for an item and returns its canonical code. The
     * item's current unit stays selectable even when it has been deactivated.
     */
    public String requireForItem(Long storeId, String value, String currentCode) {
        String code = UnitConverter.normalize(value);
        UnitMaster unit = unitMasterRepository.findByStoreIdAndUnitCodeIgnoreCase(storeId, code)
                .orElseThrow(() -> ApiException.badRequest("Select a valid unit"));
        if (unit.getStatus() != StoreStatus.ACTIVE
                && !unit.getUnitCode().equalsIgnoreCase(currentCode == null ? "" : currentCode)) {
            throw ApiException.badRequest("The selected unit is inactive");
        }
        return unit.getUnitCode();
    }

    public UnitMaster find(Long id, Long storeId) {
        return unitMasterRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Unit not found"));
    }

    private long usage(Long storeId, String code) {
        return itemRepository.countByStoreIdAndBaseUnitIgnoreCase(storeId, code)
                + purchaseDetailRepository.countByStoreIdAndUnitIgnoreCase(storeId, code)
                + salesDetailRepository.countByStoreIdAndUnitIgnoreCase(storeId, code)
                + stockAdjustmentRepository.countByStoreIdAndUnitIgnoreCase(storeId, code)
                + stockTransferDetailRepository.countByStoreIdAndUnitIgnoreCase(storeId, code);
    }

    private void apply(UnitMaster unit, UnitMasterRequest request, String code) {
        unit.setUnitCode(code);
        unit.setUnitName(request.unitName().trim());
        unit.setDescription(blankToNull(request.description()));
    }

    private UnitMasterResponse toResponse(Long storeId, UnitMaster unit) {
        return new UnitMasterResponse(unit.getId(), unit.getStoreId(), unit.getUnitCode(),
                unit.getUnitName(), unit.getDescription(), unit.getStatus().name(),
                itemRepository.countByStoreIdAndBaseUnitIgnoreCase(storeId, unit.getUnitCode()),
                unit.getCreatedAt(), unit.getUpdatedAt());
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
