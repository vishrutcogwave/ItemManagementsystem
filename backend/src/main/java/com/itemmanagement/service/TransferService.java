package com.itemmanagement.service;

import com.itemmanagement.dto.transaction.TransferRequest;
import com.itemmanagement.dto.transaction.TransferResponse;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.BranchInventory;
import com.itemmanagement.entity.Item;
import com.itemmanagement.entity.StockTransfer;
import com.itemmanagement.entity.StockTransferDetail;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.entity.enums.TransactionType;
import com.itemmanagement.entity.enums.TransferStatus;
import com.itemmanagement.entity.enums.Unit;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchInventoryRepository;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.ItemRepository;
import com.itemmanagement.repository.StockTransferDetailRepository;
import com.itemmanagement.repository.StockTransferRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PageResponse;
import com.itemmanagement.util.Pagination;
import com.itemmanagement.util.UnitConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final StockTransferRepository transferRepository;
    private final StockTransferDetailRepository detailRepository;
    private final BranchRepository branchRepository;
    private final BranchInventoryRepository inventoryRepository;
    private final ItemRepository itemRepository;
    private final InventoryService inventoryService;
    private final NumberGenerator numberGenerator;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<TransferResponse> list(Long branchId, TransferStatus status, LocalDate from,
                                               LocalDate to, String search, int page, int size) {
        Long storeId = TenantContext.storeId();
        AuthUser user = TenantContext.user();
        if (branchId != null) {
            TenantContext.requireBranch(branchId);
        }
        Page<StockTransfer> transfers = transferRepository.search(storeId, branchId,
                user.allBranches() ? null : user.branchIds(), status, from, to, clean(search),
                Pagination.of(page, size));
        Map<Long, Branch> branches = branches(storeId);
        return PageResponse.of(transfers, transfer -> toResponse(transfer, List.of(), branches, Map.of()));
    }

    @Transactional(readOnly = true)
    public TransferResponse get(Long id) {
        Long storeId = TenantContext.storeId();
        StockTransfer transfer = find(id, storeId);
        boolean canSee = TenantContext.user().canAccessBranch(transfer.getFromBranchId())
                || TenantContext.user().canAccessBranch(transfer.getToBranchId());
        if (!canSee) {
            throw ApiException.forbidden("You do not have access to this transfer");
        }
        return toResponse(transfer, detailRepository.findByTransferIdOrderByItemId(id),
                branches(storeId), items(storeId));
    }

    @Transactional
    public TransferResponse create(TransferRequest request) {
        Long storeId = TenantContext.storeId();
        TenantContext.requireBranch(request.fromBranchId());
        if (request.fromBranchId().equals(request.toBranchId())) {
            throw ApiException.badRequest("Source and destination branches must be different");
        }
        Branch from = activeBranch(request.fromBranchId(), storeId);
        Branch to = activeBranch(request.toBranchId(), storeId);
        Map<Long, Item> items = items(storeId);
        Set<Long> seen = new HashSet<>();
        List<StockTransferDetail> details = new ArrayList<>();
        for (TransferRequest.Line line : request.items()) {
            if (!seen.add(line.itemId())) {
                throw ApiException.badRequest("The same item cannot be added twice");
            }
            Item item = items.get(line.itemId());
            if (item == null) {
                throw ApiException.badRequest("Select a valid item");
            }
            if (item.getStatus() != StoreStatus.ACTIVE) {
                throw ApiException.badRequest(item.getItemName() + " is inactive");
            }
            Unit unit = UnitConverter.parse(line.unit());
            if (unit == null) {
                unit = item.getBaseUnit();
            }
            details.add(StockTransferDetail.builder()
                    .storeId(storeId)
                    .itemId(item.getId())
                    .quantity(line.quantity().setScale(3, RoundingMode.HALF_UP))
                    .unit(unit.name())
                    .baseQuantity(UnitConverter.toBase(line.quantity(), unit, item.getBaseUnit()))
                    .build());
        }
        StockTransfer transfer = transferRepository.save(StockTransfer.builder()
                .storeId(storeId)
                .transferNumber(numberGenerator.nextTransfer())
                .fromBranchId(from.getId())
                .toBranchId(to.getId())
                .transferDate(request.transferDate() == null ? LocalDate.now() : request.transferDate())
                .status(TransferStatus.PENDING)
                .notes(blankToNull(request.notes()))
                .createdBy(TenantContext.userId())
                .build());
        details.forEach(detail -> detail.setTransferId(transfer.getId()));
        detailRepository.saveAll(details);
        auditService.log("CREATE", "TRANSFER", transfer.getId(),
                "Transfer " + transfer.getTransferNumber() + " " + from.getBranchName()
                        + " -> " + to.getBranchName());
        return toResponse(transfer, details, Map.of(from.getId(), from, to.getId(), to), items);
    }

    @Transactional
    public TransferResponse approve(Long id) {
        Long storeId = TenantContext.storeId();
        StockTransfer transfer = find(id, storeId);
        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw ApiException.badRequest("Only pending transfers can be approved");
        }
        transfer.setStatus(TransferStatus.APPROVED);
        transferRepository.save(transfer);
        auditService.log("APPROVE", "TRANSFER", id, "Approved transfer " + transfer.getTransferNumber());
        return response(transfer, storeId);
    }

    @Transactional
    public TransferResponse dispatch(Long id) {
        Long storeId = TenantContext.storeId();
        StockTransfer transfer = find(id, storeId);
        if (transfer.getStatus() != TransferStatus.APPROVED) {
            throw ApiException.badRequest("Only approved transfers can be dispatched");
        }
        TenantContext.requireBranch(transfer.getFromBranchId());
        List<StockTransferDetail> details = detailRepository.findByTransferIdOrderByItemId(id);
        Map<Long, Item> items = items(storeId);
        details.stream().sorted(Comparator.comparing(StockTransferDetail::getItemId)).forEach(detail -> {
            Item item = items.get(detail.getItemId());
            if (item == null) {
                throw ApiException.badRequest("An item in this transfer is no longer available");
            }
            BranchInventory source = inventoryService.lockOrCreate(
                    storeId, transfer.getFromBranchId(), item);
            inventoryService.applyStockOut(source, item, detail.getBaseQuantity(),
                    TransactionType.TRANSFER_OUT, "TRANSFER", transfer.getId(),
                    transfer.getTransferNumber(), "Transfer to branch " + transfer.getToBranchId());
        });
        transfer.setStatus(TransferStatus.DISPATCHED);
        transferRepository.save(transfer);
        auditService.log("DISPATCH", "TRANSFER", id, "Dispatched transfer " + transfer.getTransferNumber());
        return toResponse(transfer, details, branches(storeId), items);
    }

    @Transactional
    public TransferResponse receive(Long id) {
        Long storeId = TenantContext.storeId();
        StockTransfer transfer = find(id, storeId);
        if (transfer.getStatus() != TransferStatus.DISPATCHED) {
            throw ApiException.badRequest("Only dispatched transfers can be received");
        }
        TenantContext.requireBranch(transfer.getToBranchId());
        List<StockTransferDetail> details = detailRepository.findByTransferIdOrderByItemId(id);
        Map<Long, Item> items = items(storeId);
        details.stream().sorted(Comparator.comparing(StockTransferDetail::getItemId)).forEach(detail -> {
            Item item = items.get(detail.getItemId());
            if (item == null) {
                throw ApiException.badRequest("An item in this transfer is no longer available");
            }
            BigDecimal unitCost = inventoryRepository
                    .findByStoreIdAndBranchIdAndItemId(storeId, transfer.getFromBranchId(), item.getId())
                    .map(BranchInventory::getAvgCost)
                    .orElse(BigDecimal.ZERO);
            BranchInventory destination = inventoryService.lockOrCreate(
                    storeId, transfer.getToBranchId(), item);
            inventoryService.applyStockIn(destination, item, detail.getBaseQuantity(), unitCost,
                    TransactionType.TRANSFER_IN, "TRANSFER", transfer.getId(),
                    transfer.getTransferNumber(), "Transfer from branch " + transfer.getFromBranchId());
        });
        transfer.setStatus(TransferStatus.RECEIVED);
        transferRepository.save(transfer);
        auditService.log("RECEIVE", "TRANSFER", id, "Received transfer " + transfer.getTransferNumber());
        return toResponse(transfer, details, branches(storeId), items);
    }

    @Transactional
    public TransferResponse reject(Long id) {
        Long storeId = TenantContext.storeId();
        StockTransfer transfer = find(id, storeId);
        if (transfer.getStatus() != TransferStatus.PENDING
                && transfer.getStatus() != TransferStatus.APPROVED) {
            throw ApiException.badRequest("Only pending or approved transfers can be rejected");
        }
        transfer.setStatus(TransferStatus.REJECTED);
        transferRepository.save(transfer);
        auditService.log("REJECT", "TRANSFER", id, "Rejected transfer " + transfer.getTransferNumber());
        return response(transfer, storeId);
    }

    @Transactional
    public TransferResponse cancel(Long id) {
        Long storeId = TenantContext.storeId();
        StockTransfer transfer = find(id, storeId);
        if (transfer.getStatus() == TransferStatus.RECEIVED) {
            throw ApiException.badRequest("A received transfer cannot be cancelled");
        }
        if (transfer.getStatus() == TransferStatus.DISPATCHED) {
            throw ApiException.badRequest("A dispatched transfer must be received or resolved by the destination branch");
        }
        if (transfer.getStatus() == TransferStatus.CANCELLED) {
            throw ApiException.badRequest("This transfer is already cancelled");
        }
        TenantContext.requireBranch(transfer.getFromBranchId());
        transfer.setStatus(TransferStatus.CANCELLED);
        transferRepository.save(transfer);
        auditService.log("CANCEL", "TRANSFER", id, "Cancelled transfer " + transfer.getTransferNumber());
        return response(transfer, storeId);
    }

    private TransferResponse response(StockTransfer transfer, Long storeId) {
        return toResponse(transfer, detailRepository.findByTransferIdOrderByItemId(transfer.getId()),
                branches(storeId), items(storeId));
    }

    private TransferResponse toResponse(StockTransfer transfer, List<StockTransferDetail> details,
                                        Map<Long, Branch> branches, Map<Long, Item> items) {
        Branch from = branches.get(transfer.getFromBranchId());
        Branch to = branches.get(transfer.getToBranchId());
        List<TransferResponse.LineResponse> lines = details.stream().map(detail -> {
            Item item = items.get(detail.getItemId());
            return new TransferResponse.LineResponse(detail.getId(), detail.getItemId(),
                    item == null ? "" : item.getItemCode(), item == null ? "" : item.getItemName(),
                    detail.getQuantity(), detail.getUnit(), detail.getBaseQuantity());
        }).toList();
        return new TransferResponse(transfer.getId(), transfer.getTransferNumber(),
                transfer.getFromBranchId(), from == null ? "" : from.getBranchName(),
                transfer.getToBranchId(), to == null ? "" : to.getBranchName(),
                transfer.getTransferDate(), transfer.getStatus().name(), transfer.getNotes(),
                transfer.getCreatedAt(), lines);
    }

    private StockTransfer find(Long id, Long storeId) {
        return transferRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Stock transfer not found"));
    }

    private Branch activeBranch(Long id, Long storeId) {
        Branch branch = branchRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.badRequest("Select a valid branch"));
        if (branch.getStatus() != StoreStatus.ACTIVE) {
            throw ApiException.badRequest("Branch " + branch.getBranchName() + " is inactive");
        }
        return branch;
    }

    private Map<Long, Branch> branches(Long storeId) {
        return branchRepository.findByStoreIdOrderByBranchName(storeId).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));
    }

    private Map<Long, Item> items(Long storeId) {
        return itemRepository.findByStoreIdOrderByItemName(storeId).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
