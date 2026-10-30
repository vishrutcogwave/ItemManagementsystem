package com.itemmanagement.service;

import com.itemmanagement.dto.master.TaxMasterRequest;
import com.itemmanagement.dto.master.TaxMasterResponse;
import com.itemmanagement.entity.TaxMaster;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.entity.enums.TaxType;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.ItemRepository;
import com.itemmanagement.repository.PurchaseDetailRepository;
import com.itemmanagement.repository.SalesDetailRepository;
import com.itemmanagement.repository.TaxMasterRepository;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.GstCalculator;
import com.itemmanagement.util.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaxMasterService {

    private final TaxMasterRepository taxMasterRepository;
    private final ItemRepository itemRepository;
    private final PurchaseDetailRepository purchaseDetailRepository;
    private final SalesDetailRepository salesDetailRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<TaxMasterResponse> list(String search, StoreStatus status, int page, int size) {
        Long storeId = TenantContext.storeId();
        Page<TaxMaster> taxes = taxMasterRepository.search(storeId, clean(search), status,
                PageRequest.of(page, Math.min(Math.max(size, 1), 100)));
        return PageResponse.of(taxes, tax -> toResponse(storeId, tax));
    }

    @Transactional(readOnly = true)
    public List<TaxMasterResponse> options() {
        Long storeId = TenantContext.storeId();
        return taxMasterRepository.findByStoreIdOrderByGstPercentageAscTaxNameAsc(storeId).stream()
                .map(tax -> toResponse(storeId, tax)).toList();
    }

    @Transactional(readOnly = true)
    public TaxMasterResponse get(Long id) {
        Long storeId = TenantContext.storeId();
        return toResponse(storeId, find(id, storeId));
    }

    @Transactional
    public TaxMasterResponse create(TaxMasterRequest request) {
        Long storeId = TenantContext.storeId();
        String name = request.taxName().trim();
        if (taxMasterRepository.existsByStoreIdAndTaxNameIgnoreCase(storeId, name)) {
            throw ApiException.conflict("A tax with this name already exists");
        }
        TaxMaster tax = new TaxMaster();
        tax.setStoreId(storeId);
        tax.setStatus(request.status() == null ? StoreStatus.ACTIVE : request.status());
        apply(tax, request, name);
        taxMasterRepository.save(tax);
        auditService.log("CREATE", "TAX", tax.getId(), "Created tax " + name);
        return toResponse(storeId, tax);
    }

    @Transactional
    public TaxMasterResponse update(Long id, TaxMasterRequest request) {
        Long storeId = TenantContext.storeId();
        TaxMaster tax = find(id, storeId);
        String name = request.taxName().trim();
        if (!tax.getTaxName().equalsIgnoreCase(name)
                && taxMasterRepository.existsByStoreIdAndTaxNameIgnoreCase(storeId, name)) {
            throw ApiException.conflict("A tax with this name already exists");
        }
        apply(tax, request, name);
        taxMasterRepository.save(tax);
        auditService.log("UPDATE", "TAX", id, "Updated tax " + name);
        return toResponse(storeId, tax);
    }

    @Transactional
    public TaxMasterResponse updateStatus(Long id, StoreStatus status) {
        Long storeId = TenantContext.storeId();
        TaxMaster tax = find(id, storeId);
        tax.setStatus(status);
        taxMasterRepository.save(tax);
        auditService.log(status == StoreStatus.ACTIVE ? "ACTIVATE" : "DEACTIVATE", "TAX", id,
                "Tax status changed to " + status);
        return toResponse(storeId, tax);
    }

    @Transactional
    public void delete(Long id) {
        Long storeId = TenantContext.storeId();
        TaxMaster tax = find(id, storeId);
        long items = itemRepository.countByStoreIdAndTaxId(storeId, id);
        long purchases = purchaseDetailRepository.countByStoreIdAndTaxId(storeId, id);
        long sales = salesDetailRepository.countByStoreIdAndTaxId(storeId, id);
        if (items > 0 || purchases > 0 || sales > 0) {
            throw ApiException.badRequest("This tax is used by " + items + " item(s), "
                    + purchases + " purchase line(s) and " + sales
                    + " sales line(s). Deactivate it instead of deleting.");
        }
        taxMasterRepository.delete(tax);
        auditService.log("DELETE", "TAX", id, "Deleted tax " + tax.getTaxName());
    }

    public TaxMaster find(Long id, Long storeId) {
        return taxMasterRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Tax not found"));
    }

    private void apply(TaxMaster tax, TaxMasterRequest request, String name) {
        boolean exempt = request.taxType() == TaxType.EXEMPT;
        BigDecimal gst = GstCalculator.gstForType(request.gstPercentage(), exempt);
        BigDecimal half = gst.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        tax.setTaxName(name);
        tax.setDescription(blankToNull(request.description()));
        tax.setGstPercentage(gst);
        tax.setCgstPercentage(half);
        tax.setSgstPercentage(gst.subtract(half).setScale(2, RoundingMode.HALF_UP));
        tax.setIgstPercentage(gst);
        tax.setTaxType(exempt ? TaxType.EXEMPT : TaxType.GST);
    }

    private TaxMasterResponse toResponse(Long storeId, TaxMaster tax) {
        return new TaxMasterResponse(tax.getId(), tax.getStoreId(), tax.getTaxName(),
                tax.getDescription(), tax.getGstPercentage(), tax.getCgstPercentage(),
                tax.getSgstPercentage(), tax.getIgstPercentage(), tax.getTaxType().name(),
                tax.getStatus().name(), itemRepository.countByStoreIdAndTaxId(storeId, tax.getId()),
                tax.getCreatedAt(), tax.getUpdatedAt());
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
