package com.itemmanagement.service;

import com.itemmanagement.dto.imports.ImportBatchResponse;
import com.itemmanagement.dto.imports.ImportPreviewResponse;
import com.itemmanagement.dto.imports.ImportRowResponse;
import com.itemmanagement.dto.imports.ImportSummary;
import com.itemmanagement.dto.master.UnitMasterRequest;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.BranchInventory;
import com.itemmanagement.entity.Category;
import com.itemmanagement.entity.Item;
import com.itemmanagement.entity.ItemImportBatch;
import com.itemmanagement.entity.ItemImportRow;
import com.itemmanagement.entity.TaxMaster;
import com.itemmanagement.entity.UnitMaster;
import com.itemmanagement.entity.enums.ImportAction;
import com.itemmanagement.entity.enums.ImportBatchStatus;
import com.itemmanagement.entity.enums.ImportValidationStatus;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.entity.enums.TransactionType;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchInventoryRepository;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.CategoryRepository;
import com.itemmanagement.repository.InventoryTransactionRepository;
import com.itemmanagement.repository.ItemImportBatchRepository;
import com.itemmanagement.repository.ItemImportRowRepository;
import com.itemmanagement.repository.ItemRepository;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.repository.TaxMasterRepository;
import com.itemmanagement.repository.UnitMasterRepository;
import com.itemmanagement.security.AuthUser;
import com.itemmanagement.security.TenantContext;
import com.itemmanagement.util.PageResponse;
import com.itemmanagement.util.Pagination;
import com.itemmanagement.util.UnitConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ItemImportService {

    private static final Pattern ITEM_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{2,50}$");
    private static final Pattern UNIT_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,20}$");
    private static final BigDecimal DEFAULT_MIN_STOCK = new BigDecimal("5");
    private static final BigDecimal MAX_RATE = new BigDecimal("9999999999.99");
    private static final BigDecimal MAX_ROW_RATE = new BigDecimal("999999999999.99");
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999999999.999");
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final ItemImportExcelService excelService;
    private final ItemImportBatchRepository batchRepository;
    private final ItemImportRowRepository rowRepository;
    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final TaxMasterRepository taxMasterRepository;
    private final BranchRepository branchRepository;
    private final BranchInventoryRepository inventoryRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final StoreRepository storeRepository;
    private final UnitMasterRepository unitMasterRepository;
    private final UnitMasterService unitMasterService;
    private final InventoryService inventoryService;
    private final NumberGenerator numberGenerator;
    private final AuditService auditService;
    private final BarcodeService barcodeService;
    private final PlatformTransactionManager transactionManager;

    @Transactional(readOnly = true)
    public byte[] template() {
        Long storeId = TenantContext.storeId();
        return excelService.template(
                categoryRepository.findByStoreIdOrderByCategoryName(storeId),
                taxMasterRepository.findByStoreIdOrderByGstPercentageAscTaxNameAsc(storeId),
                unitMasterRepository.findByStoreIdOrderByUnitCodeAsc(storeId),
                "The branch you select when uploading the file");
    }

    @Transactional
    public ImportPreviewResponse preview(MultipartFile file, Long branchId) {
        Long storeId = TenantContext.storeId();
        TenantContext.requireBranch(branchId);
        Branch branch = activeBranch(branchId, storeId);

        byte[] content = read(file);
        String fileName = fileName(file);
        String hash = sha256(content);
        ItemImportExcelService.ParsedWorkbook parsed = excelService.parse(content);

        ImportContext context = context(storeId, branchId);
        Optional<ItemImportBatch> previous = batchRepository
                .findFirstByStoreIdAndBranchIdAndFileHashAndStatusOrderByIdAsc(
                        storeId, branchId, hash, ImportBatchStatus.COMPLETED);

        ItemImportBatch batch = batchRepository.save(ItemImportBatch.builder()
                .storeId(storeId)
                .branchId(branchId)
                .importNumber(numberGenerator.nextImportNumber())
                .fileName(fileName)
                .fileHash(hash)
                .status(ImportBatchStatus.PREVIEWED)
                .uploadedBy(TenantContext.userId())
                .uploadedByUsername(TenantContext.user().username())
                .build());

        List<ItemImportRow> rows = new ArrayList<>();
        if (previous.isPresent()) {
            String message = "This exact file was already imported as "
                    + previous.get().getImportNumber() + ". It cannot be imported again for this branch.";
            for (ItemImportExcelService.ParsedRow parsedRow : parsed.rows()) {
                ItemImportRow row = toRow(storeId, batch.getId(), parsedRow, context);
                row.setImportAction(ImportAction.ALREADY_IMPORTED);
                row.setValidationStatus(ImportValidationStatus.VALID);
                row.setErrorMessage(message);
                rows.add(row);
            }
        } else {
            Map<String, Integer> codeSeen = new HashMap<>();
            Map<String, Integer> nameSeen = new HashMap<>();
            for (ItemImportExcelService.ParsedRow parsedRow : parsed.rows()) {
                ItemImportRow row = toRow(storeId, batch.getId(), parsedRow, context);
                if (row.isParseFailed()) {
                    rows.add(row);
                    continue;
                }
                Plan plan = plan(row, context, branch, codeSeen, nameSeen);
                row.setImportAction(plan.action());
                row.setValidationStatus(plan.status());
                row.setErrorMessage(plan.error());
                if (plan.item() != null) {
                    row.setItemId(plan.item().getId());
                    if (row.getItemCode() == null) {
                        row.setItemCode(plan.item().getItemCode());
                    }
                }
                rows.add(row);
            }
        }
        rowRepository.saveAll(rows);

        Counts counts = countActions(rows);
        UnitPlan unitPlan = unitPlan(rows, context);
        batch.setTotalRows(rows.size());
        batch.setNewCategories(missingCategoryCount(rows, context.categoriesByKey().keySet()));
        batch.setNewUnits(unitPlan.newUnits());
        batch.setReusedUnits(unitPlan.reusedUnits());
        batch.setNewItems(counts.newItems());
        batch.setExistingItems(counts.existingItems());
        batch.setStockUpdates(plannedStockUpdates(rows));
        batch.setDuplicateRows(counts.duplicateRows());
        batch.setInvalidRows(counts.invalidRows());
        batch.setAlreadyImportedRows(counts.alreadyImportedRows());
        batch.setFailedRows(counts.duplicateRows() + counts.invalidRows());
        batchRepository.save(batch);

        return response(batch, branch.getBranchName(), rows);
    }

    public ImportPreviewResponse confirm(Long batchId) {
        Long storeId = TenantContext.storeId();
        ItemImportBatch batch = find(batchId, storeId);
        TenantContext.requireBranch(batch.getBranchId());
        Branch branch = activeBranch(batch.getBranchId(), storeId);

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        try {
            return template.execute(status -> doConfirm(batchId, storeId, branch));
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Item import {} failed and was rolled back", batchId, e);
            markFailed(batchId, storeId, e);
            throw ApiException.badRequest("The import could not be completed and was rolled back. "
                    + "Please review the file and upload it again.");
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<ImportBatchResponse> history(int page, int size) {
        Long storeId = TenantContext.storeId();
        AuthUser user = TenantContext.user();
        int safeSize = Pagination.clampSize(size, 50);
        int safePage = Pagination.clampPage(page);
        Pageable pageable = Pagination.of(page, size, 50);
        if (!user.allBranches() && user.branchIds().isEmpty()) {
            return new PageResponse<>(List.of(), safePage, safeSize, 0, 0);
        }
        Page<ItemImportBatch> result = user.allBranches()
                ? batchRepository.findByStoreIdOrderByCreatedAtDesc(storeId, pageable)
                : batchRepository.findByStoreIdAndBranchIdInOrderByCreatedAtDesc(
                        storeId, user.branchIds(), pageable);
        Map<Long, Branch> branches = branchRepository.findByStoreIdOrderByBranchName(storeId).stream()
                .collect(Collectors.toMap(Branch::getId, branch -> branch));
        return PageResponse.of(result, batch -> batchResponse(batch, branches.get(batch.getBranchId())));
    }

    @Transactional(readOnly = true)
    public ImportPreviewResponse detail(Long batchId) {
        Long storeId = TenantContext.storeId();
        ItemImportBatch batch = find(batchId, storeId);
        if (!TenantContext.user().canAccessBranch(batch.getBranchId())) {
            throw ApiException.forbidden("You do not have access to this branch");
        }
        Branch branch = branchRepository.findByIdAndStoreId(batch.getBranchId(), storeId).orElse(null);
        List<ItemImportRow> rows = rowRepository.findByBatchIdOrderByRowNumber(batch.getId());
        return response(batch, branch == null ? "" : branch.getBranchName(), rows);
    }

    private ImportPreviewResponse doConfirm(Long batchId, Long storeId, Branch branch) {
        storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> ApiException.notFound("Store not found"));
        ItemImportBatch batch = batchRepository.findForUpdate(batchId, storeId)
                .orElseThrow(() -> ApiException.notFound("Import not found"));

        if (batch.getStatus() == ImportBatchStatus.COMPLETED) {
            throw ApiException.conflict("This file has already been imported.");
        }
        if (batch.getStatus() == ImportBatchStatus.FAILED) {
            throw ApiException.badRequest("This import attempt failed and was rolled back. "
                    + "Upload the file again to start a new preview.");
        }
        if (batchRepository.existsByStoreIdAndBranchIdAndFileHashAndStatusAndIdNot(
                storeId, batch.getBranchId(), batch.getFileHash(),
                ImportBatchStatus.COMPLETED, batch.getId())) {
            throw ApiException.conflict("This file has already been imported for this branch.");
        }

        List<ItemImportRow> rows = rowRepository.findByBatchIdOrderByRowNumber(batch.getId());
        if (rows.isEmpty()) {
            throw ApiException.badRequest("This import does not contain any rows. Upload the file again.");
        }

        ImportContext context = context(storeId, batch.getBranchId());
        Map<String, Integer> codeSeen = new HashMap<>();
        Map<String, Integer> nameSeen = new HashMap<>();
        for (ItemImportRow row : rows) {
            if (row.isParseFailed()) {
                row.setImportAction(ImportAction.VALIDATION_ERROR);
                row.setValidationStatus(ImportValidationStatus.INVALID);
                row.setItemId(null);
                continue;
            }
            Plan plan = plan(row, context, branch, codeSeen, nameSeen);
            row.setImportAction(plan.action());
            row.setValidationStatus(plan.status());
            row.setErrorMessage(plan.error());
            row.setItemId(plan.item() == null ? null : plan.item().getId());
            if (plan.item() != null && row.getItemCode() == null) {
                row.setItemCode(plan.item().getItemCode());
            }
        }

        Counts planned = countActions(rows);
        if (planned.invalidRows() > 0 || planned.duplicateRows() > 0) {
            throw ApiException.badRequest(validationMessage(rows));
        }
        if (planned.newItems() + planned.existingItems() == 0) {
            throw ApiException.badRequest("Nothing to import. Every row is already imported for this branch.");
        }

        int createdUnits = prepareUnits(rows, context, storeId);
        int reusedUnits = reusedUnits(rows, context);

        Map<String, Category> categories = new LinkedHashMap<>(context.categoriesByKey());
        int createdCategories = 0;
        for (ItemImportRow row : rows) {
            if (row.getImportAction() != ImportAction.NEW_ITEM) {
                continue;
            }
            String key = key(row.getCategoryName());
            if (key != null && !categories.containsKey(key)) {
                Category category = categoryRepository.save(Category.builder()
                        .storeId(storeId)
                        .categoryName(clean(row.getCategoryName()))
                        .description(null)
                        .status(StoreStatus.ACTIVE)
                        .build());
                categories.put(key, category);
                createdCategories++;
            }
        }

        int stockUpdates = 0;
        for (ItemImportRow row : rows) {
            if (row.getImportAction() == ImportAction.NEW_ITEM) {
                Category category = categories.get(key(row.getCategoryName()));
                TaxMaster tax = context.taxesByKey().get(key(row.getTaxName()));
                if (category == null || tax == null) {
                    throw new IllegalStateException("Import row " + row.getRowNumber()
                            + " lost its category or tax reference");
                }
                String code = row.getItemCode();
                if (code == null) {
                    code = generateItemCode(storeId);
                } else if (itemRepository.existsByStoreIdAndItemCodeIgnoreCase(storeId, code)) {
                    throw ApiException.conflict("Item code " + code
                            + " was created by another user. Upload the file again.");
                }
                BarcodeService.ResolvedBarcode barcode = barcodeService.generate(
                        storeId, com.itemmanagement.entity.enums.BarcodeFormat.CODE128);
                Item item = itemRepository.save(Item.builder()
                        .storeId(storeId)
                        .categoryId(category.getId())
                        .taxId(tax.getId())
                        .itemCode(code)
                        .barcode(barcode.value())
                        .barcodeFormat(barcode.format())
                        .itemName(clean(row.getItemName()))
                        .description(clean(row.getDescription()))
                        .baseUnit(row.getUnit())
                        .rate(row.getRate())
                        .minStockLevel(row.getMinimumStock() == null
                                ? DEFAULT_MIN_STOCK : row.getMinimumStock())
                        .status(StoreStatus.ACTIVE)
                        .build());
                row.setItemId(item.getId());
                row.setItemCode(code);
                if (applyOpeningStock(item, row.getOpeningStock(), storeId, batch)) {
                    stockUpdates++;
                }
            } else if (row.getImportAction() == ImportAction.EXISTING_ITEM_OPENING_STOCK) {
                Item item = itemRepository.findByIdAndStoreId(row.getItemId(), storeId)
                        .orElseThrow(() -> new IllegalStateException("Item " + row.getItemId()
                                + " disappeared during the import"));
                if (applyOpeningStock(item, row.getOpeningStock(), storeId, batch)) {
                    stockUpdates++;
                } else {
                    row.setImportAction(ImportAction.ALREADY_IMPORTED);
                    row.setErrorMessage("Opening stock for this item at " + branch.getBranchName()
                            + " was already recorded; the row was skipped.");
                }
            }
        }

        Counts counts = countActions(rows);
        batch.setStatus(ImportBatchStatus.COMPLETED);
        batch.setCompletedAt(Instant.now());
        batch.setFailureMessage(null);
        batch.setTotalRows(rows.size());
        batch.setSuccessRows(counts.newItems() + counts.existingItems());
        batch.setFailedRows(counts.duplicateRows() + counts.invalidRows());
        batch.setNewCategories(createdCategories);
        batch.setNewUnits(createdUnits);
        batch.setReusedUnits(reusedUnits);
        batch.setNewItems(counts.newItems());
        batch.setExistingItems(counts.existingItems());
        batch.setStockUpdates(stockUpdates);
        batch.setDuplicateRows(counts.duplicateRows());
        batch.setInvalidRows(counts.invalidRows());
        batch.setAlreadyImportedRows(counts.alreadyImportedRows());
        batchRepository.save(batch);

        auditService.log("IMPORT", "ITEM_IMPORT", batch.getId(),
                "Imported " + batch.getSuccessRows() + " of " + rows.size()
                        + " rows from " + batch.getFileName()
                        + " into " + branch.getBranchName());

        return response(batch, branch.getBranchName(), rows);
    }

    private boolean applyOpeningStock(Item item, BigDecimal quantity, Long storeId,
                                      ItemImportBatch batch) {
        if (quantity == null || quantity.signum() <= 0) {
            return false;
        }
        BranchInventory inventory = inventoryService.lockOrCreate(storeId, batch.getBranchId(), item);
        if (inventory.getCurrentStock().signum() > 0
                || transactionRepository.existsByStoreIdAndBranchIdAndItemId(
                storeId, batch.getBranchId(), item.getId())) {
            return false;
        }
        inventoryService.applyStockIn(inventory, item, quantity, item.getRate(),
                TransactionType.OPENING_STOCK, "ITEM_IMPORT", batch.getId(),
                batch.getImportNumber(), "Opening stock imported from " + batch.getFileName());
        return true;
    }

    private Plan plan(ItemImportRow row, ImportContext context, Branch branch,
                      Map<String, Integer> codeSeen, Map<String, Integer> nameSeen) {
        List<String> errors = new ArrayList<>();
        String code = row.getItemCode() == null ? null
                : row.getItemCode().trim().toUpperCase(Locale.ROOT);
        String name = clean(row.getItemName());
        String categoryName = clean(row.getCategoryName());
        String taxName = clean(row.getTaxName());
        String unit = resolveUnit(row.getUnit(), context);
        String rawUnit = clean(row.getUnit());
        String autoUnitCode = null;
        BigDecimal rate = row.getRate();
        BigDecimal openingStock = row.getOpeningStock();
        BigDecimal minimumStock = row.getMinimumStock();

        if (code != null && !ITEM_CODE_PATTERN.matcher(code).matches()) {
            errors.add("Item code must be 2-50 letters, numbers, dot, underscore or hyphen");
        }
        if (name == null) {
            errors.add("Item name is required");
        } else if (name.length() > 150) {
            errors.add("Item name must be 150 characters or less");
        }
        if (categoryName == null) {
            errors.add("Category name is required");
        } else if (categoryName.length() > 120) {
            errors.add("Category name must be 120 characters or less");
        }
        if (rawUnit == null) {
            errors.add("Unit is required");
        } else if (unit == null) {
            autoUnitCode = UnitConverter.normalize(rawUnit);
            if (!UNIT_CODE_PATTERN.matcher(autoUnitCode).matches()) {
                errors.add("Unit '" + rawUnit + "' is not in Unit Master and cannot be created automatically. "
                        + "Unit codes allow 1-20 letters, numbers, dot, underscore or hyphen.");
            }
        }
        if (rate == null) {
            errors.add("Rate is required");
        } else if (rate.signum() <= 0) {
            errors.add("Rate must be greater than zero");
        } else if (rate.compareTo(MAX_RATE) > 0) {
            errors.add("Rate is too large");
        }
        if (taxName == null) {
            errors.add("Tax name is required");
        } else if (taxName.length() > 120) {
            errors.add("Tax name must be 120 characters or less");
        }
        if (openingStock != null && openingStock.signum() < 0) {
            errors.add("Opening stock quantity cannot be negative");
        } else if (openingStock != null && openingStock.compareTo(MAX_QUANTITY) > 0) {
            errors.add("Opening stock quantity is too large");
        }
        if (minimumStock != null && minimumStock.signum() < 0) {
            errors.add("Minimum stock quantity cannot be negative");
        } else if (minimumStock != null && minimumStock.compareTo(MAX_QUANTITY) > 0) {
            errors.add("Minimum stock quantity is too large");
        }
        if (row.getDescription() != null && row.getDescription().length() > 255) {
            errors.add("Description must be 255 characters or less");
        }
        if (!errors.isEmpty()) {
            return Plan.invalid(join(errors));
        }

        String effectiveUnit = unit == null ? autoUnitCode : unit;
        TaxMaster tax = context.taxesByKey().get(key(taxName));
        if (tax == null) {
            return Plan.invalid("Tax '" + taxName + "' is not available for this store");
        }

        Category category = context.categoriesByKey().get(key(categoryName));
        Item existing = null;
        if (code != null) {
            existing = context.itemsByCode().get(code);
            if (existing != null) {
                Category itemCategory = context.categoryById().get(existing.getCategoryId());
                String itemCategoryName = itemCategory == null ? "unknown" : itemCategory.getCategoryName();
                if (category == null || !category.getId().equals(existing.getCategoryId())) {
                    return Plan.invalid("Item code " + code + " already exists in category '"
                            + itemCategoryName + "', not '" + categoryName + "'");
                }
                if (effectiveUnit == null || !existing.getBaseUnit().equalsIgnoreCase(effectiveUnit)) {
                    return Plan.invalid("Item code " + code + " uses unit "
                            + existing.getBaseUnit() + ", not "
                            + (effectiveUnit == null ? rawUnit : effectiveUnit));
                }
            }
        } else if (category != null) {
            List<Item> matches = context.itemsByNameCategory()
                    .getOrDefault(key(name) + "|" + category.getId(), List.of());
            if (matches.size() > 1) {
                return Plan.invalid("Multiple existing items use this name in category '"
                        + categoryName + "'. Add an item code to identify the item");
            }
            if (matches.size() == 1) {
                existing = matches.get(0);
            }
        }

        if (existing == null && category != null && category.getStatus() != StoreStatus.ACTIVE) {
            return Plan.invalid("Category '" + categoryName + "' is inactive");
        }

        if (code != null) {
            Integer previous = codeSeen.get(code);
            if (previous != null) {
                return Plan.duplicate("Duplicate of row " + previous
                        + ": item code " + code + " appears more than once");
            }
            codeSeen.put(code, row.getRowNumber());
        }
        if (code == null) {
            String nameKey = key(name) + "|" + key(categoryName);
            Integer previous = nameSeen.get(nameKey);
            if (previous != null) {
                return Plan.duplicate("Duplicate of row " + previous + ": " + name
                        + " in category " + categoryName + " appears more than once");
            }
            nameSeen.put(nameKey, row.getRowNumber());
        }

        if (existing != null) {
            if (context.alreadyStocked().contains(existing.getId())) {
                return new Plan(ImportAction.ALREADY_IMPORTED, ImportValidationStatus.VALID,
                        "Opening stock for this item at " + branch.getBranchName()
                                + " already exists; the row was skipped.", existing);
            }
            if (openingStock != null && openingStock.signum() > 0) {
                return new Plan(ImportAction.EXISTING_ITEM_OPENING_STOCK,
                        ImportValidationStatus.VALID, null, existing);
            }
            return new Plan(ImportAction.EXISTING_ITEM_NO_STOCK, ImportValidationStatus.VALID,
                    "No opening stock quantity supplied; the existing item master is unchanged.",
                    existing);
        }

        return new Plan(ImportAction.NEW_ITEM, ImportValidationStatus.VALID, null, null);
    }

    private ItemImportRow toRow(Long storeId, Long batchId,
                                ItemImportExcelService.ParsedRow parsed, ImportContext context) {
        List<String> errors = new ArrayList<>();
        String code = parsed.itemCode() == null ? null
                : parsed.itemCode().trim().toUpperCase(Locale.ROOT);
        String name = clean(parsed.itemName());
        String category = clean(parsed.categoryName());
        String tax = clean(parsed.taxName());
        String description = clean(parsed.description());
        if (code != null && code.length() > 50) {
            errors.add("Item code must be 50 characters or less");
        }
        if (name != null && name.length() > 150) {
            errors.add("Item name must be 150 characters or less");
        }
        if (category != null && category.length() > 120) {
            errors.add("Category name must be 120 characters or less");
        }
        if (tax != null && tax.length() > 120) {
            errors.add("Tax name must be 120 characters or less");
        }
        if (description != null && description.length() > 255) {
            errors.add("Description must be 255 characters or less");
        }
        BigDecimal rate = parseDecimal(parsed.rate(), "Rate", errors);
        if (rate != null) {
            if (rate.abs().compareTo(MAX_ROW_RATE) > 0) {
                errors.add("Rate is too large");
                rate = null;
            } else {
                rate = scale(rate, 2);
            }
        }
        BigDecimal opening = parseDecimal(parsed.openingStock(), "Opening stock quantity", errors);
        if (opening != null) {
            if (opening.abs().compareTo(MAX_QUANTITY) > 0) {
                errors.add("Opening stock quantity is too large");
                opening = null;
            } else {
                opening = scale(opening, 3);
            }
        }
        BigDecimal minimum = parseDecimal(parsed.minimumStock(), "Minimum stock quantity", errors);
        if (minimum != null) {
            if (minimum.abs().compareTo(MAX_QUANTITY) > 0) {
                errors.add("Minimum stock quantity is too large");
                minimum = null;
            } else {
                minimum = scale(minimum, 3);
            }
        }
        String unit = resolveUnit(parsed.unit(), context);
        String rawUnit = clean(parsed.unit());
        if (unit == null && rawUnit != null && rawUnit.length() > 20) {
            errors.add("Unit must be 20 characters or less when it is not already in Unit Master");
        }

        return ItemImportRow.builder()
                .batchId(batchId)
                .storeId(storeId)
                .rowNumber(parsed.rowNumber())
                .itemCode(truncate(code, 50))
                .itemName(truncate(name, 150))
                .categoryName(truncate(category, 120))
                .unit(unit == null ? truncate(clean(parsed.unit()), 20) : unit)
                .rate(rate)
                .taxName(truncate(tax, 120))
                .openingStock(opening)
                .minimumStock(minimum)
                .description(truncate(description, 255))
                .parseFailed(!errors.isEmpty())
                .importAction(ImportAction.VALIDATION_ERROR)
                .validationStatus(ImportValidationStatus.INVALID)
                .errorMessage(errors.isEmpty() ? null : join(errors))
                .build();
    }

    private ImportContext context(Long storeId, Long branchId) {
        List<Category> categories = categoryRepository.findByStoreIdOrderByCategoryName(storeId);
        Map<String, Category> categoriesByKey = new LinkedHashMap<>();
        Map<Long, Category> categoryById = new HashMap<>();
        for (Category category : categories) {
            categoriesByKey.putIfAbsent(key(category.getCategoryName()), category);
            categoryById.put(category.getId(), category);
        }

        Map<String, TaxMaster> taxesByKey = new HashMap<>();
        for (TaxMaster tax : taxMasterRepository.findByStoreIdOrderByGstPercentageAscTaxNameAsc(storeId)) {
            taxesByKey.putIfAbsent(key(tax.getTaxName()), tax);
        }

        Map<String, String> unitsByKey = new HashMap<>();
        for (UnitMaster unit : unitMasterRepository.findByStoreIdOrderByUnitCodeAsc(storeId)) {
            unitsByKey.putIfAbsent(key(unit.getUnitCode()), unit.getUnitCode());
            unitsByKey.putIfAbsent(key(unit.getUnitName()), unit.getUnitCode());
        }

        List<Item> items = itemRepository.findByStoreIdOrderByItemName(storeId);
        Map<String, Item> itemsByCode = new HashMap<>();
        Map<String, List<Item>> itemsByNameCategory = new HashMap<>();
        for (Item item : items) {
            itemsByCode.putIfAbsent(item.getItemCode().toUpperCase(Locale.ROOT), item);
            itemsByNameCategory
                    .computeIfAbsent(key(item.getItemName()) + "|" + item.getCategoryId(),
                            ignored -> new ArrayList<>())
                    .add(item);
        }

        Set<Long> alreadyStocked = new HashSet<>(
                transactionRepository.findItemIdsWithTransactions(storeId, branchId));
        for (BranchInventory inventory : inventoryRepository.findByStoreIdAndBranchId(storeId, branchId)) {
            if (inventory.getCurrentStock().signum() > 0) {
                alreadyStocked.add(inventory.getItemId());
            }
        }

        return new ImportContext(categoriesByKey, categoryById, taxesByKey, unitsByKey,
                itemsByCode, itemsByNameCategory, alreadyStocked);
    }

    private String generateItemCode(Long storeId) {
        for (int attempt = 0; attempt < 50; attempt++) {
            String code = numberGenerator.nextItemCode();
            if (!itemRepository.existsByStoreIdAndItemCodeIgnoreCase(storeId, code)) {
                return code;
            }
        }
        throw new IllegalStateException("Unable to generate a unique item code");
    }

    private ImportPreviewResponse response(ItemImportBatch batch, String branchName,
                                           List<ItemImportRow> rows) {
        ImportSummary summary = new ImportSummary(
                batch.getTotalRows(),
                batch.getNewCategories(),
                batch.getNewUnits(),
                batch.getReusedUnits(),
                batch.getNewItems(),
                batch.getExistingItems(),
                batch.getStockUpdates(),
                batch.getDuplicateRows(),
                batch.getInvalidRows(),
                batch.getAlreadyImportedRows(),
                batch.getNewItems() + batch.getExistingItems());
        return new ImportPreviewResponse(batch.getId(), batch.getImportNumber(), batch.getFileName(),
                batch.getBranchId(), branchName, batch.getStatus().name(), batch.getCreatedAt(),
                batch.getCompletedAt(), batch.getFailureMessage(), summary,
                rows.stream().map(this::rowResponse).toList());
    }

    private ImportRowResponse rowResponse(ItemImportRow row) {
        return new ImportRowResponse(row.getId(), row.getRowNumber(), row.getItemCode(),
                row.getItemName(), row.getCategoryName(), row.getUnit(), row.getRate(),
                row.getTaxName(), row.getOpeningStock(), row.getMinimumStock(),
                row.getDescription(), row.getItemId(), row.getImportAction().name(),
                row.getValidationStatus().name(), row.getErrorMessage());
    }

    private ImportBatchResponse batchResponse(ItemImportBatch batch, Branch branch) {
        return new ImportBatchResponse(batch.getId(), batch.getImportNumber(), batch.getFileName(),
                batch.getBranchId(), branch == null ? "" : branch.getBranchName(),
                batch.getStatus().name(), batch.getTotalRows(), batch.getSuccessRows(),
                batch.getFailedRows(), batch.getNewCategories(), batch.getNewItems(),
                batch.getExistingItems(), batch.getStockUpdates(), batch.getDuplicateRows(),
                batch.getInvalidRows(), batch.getAlreadyImportedRows(), batch.getFailureMessage(),
                batch.getUploadedByUsername(), batch.getCreatedAt(), batch.getCompletedAt());
    }

    private Counts countActions(List<ItemImportRow> rows) {
        int newItems = 0;
        int existingItems = 0;
        int duplicateRows = 0;
        int invalidRows = 0;
        int alreadyImported = 0;
        for (ItemImportRow row : rows) {
            switch (row.getImportAction()) {
                case NEW_ITEM -> newItems++;
                case EXISTING_ITEM_OPENING_STOCK, EXISTING_ITEM_NO_STOCK -> existingItems++;
                case DUPLICATE_ROW -> duplicateRows++;
                case VALIDATION_ERROR -> invalidRows++;
                case ALREADY_IMPORTED -> alreadyImported++;
            }
        }
        return new Counts(newItems, existingItems, duplicateRows, invalidRows, alreadyImported);
    }

    private int plannedStockUpdates(List<ItemImportRow> rows) {
        int count = 0;
        for (ItemImportRow row : rows) {
            if (row.getImportAction() == ImportAction.EXISTING_ITEM_OPENING_STOCK) {
                count++;
            } else if (row.getImportAction() == ImportAction.NEW_ITEM
                    && row.getOpeningStock() != null && row.getOpeningStock().signum() > 0) {
                count++;
            }
        }
        return count;
    }

    private int missingCategoryCount(List<ItemImportRow> rows, Set<String> existingKeys) {
        Set<String> missing = new LinkedHashSet<>();
        for (ItemImportRow row : rows) {
            if (row.getImportAction() == ImportAction.NEW_ITEM) {
                String categoryKey = key(row.getCategoryName());
                if (categoryKey != null && !existingKeys.contains(categoryKey)) {
                    missing.add(categoryKey);
                }
            }
        }
        return missing.size();
    }

    private String validationMessage(List<ItemImportRow> rows) {
        List<String> invalid = rows.stream()
                .filter(row -> row.getValidationStatus() == ImportValidationStatus.INVALID)
                .map(row -> "row " + row.getRowNumber()).toList();
        List<String> duplicates = rows.stream()
                .filter(row -> row.getImportAction() == ImportAction.DUPLICATE_ROW)
                .map(row -> "row " + row.getRowNumber()).toList();
        StringBuilder message = new StringBuilder("Import cancelled. ");
        if (!invalid.isEmpty()) {
            message.append("Invalid rows: ").append(String.join(", ", invalid)).append(". ");
        }
        if (!duplicates.isEmpty()) {
            message.append("Duplicate rows: ").append(String.join(", ", duplicates)).append(". ");
        }
        message.append("Fix the file and upload it again.");
        return message.toString();
    }

    private void markFailed(Long batchId, Long storeId, RuntimeException error) {
        try {
            TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
            requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            requiresNew.executeWithoutResult(status -> batchRepository
                    .findByIdAndStoreId(batchId, storeId)
                    .ifPresent(batch -> {
                        batch.setStatus(ImportBatchStatus.FAILED);
                        batch.setCompletedAt(Instant.now());
                        batch.setFailureMessage(truncate(rootMessage(error), 500));
                        // Nothing was committed, so no unit or item counts survive.
                        batch.setNewUnits(0);
                        batch.setReusedUnits(0);
                        batchRepository.save(batch);
                    }));
        } catch (Exception e) {
            log.error("Unable to record the failed import {}", batchId, e);
        }
    }

    private ItemImportBatch find(Long batchId, Long storeId) {
        return batchRepository.findByIdAndStoreId(batchId, storeId)
                .orElseThrow(() -> ApiException.notFound("Import not found"));
    }

    private Branch activeBranch(Long branchId, Long storeId) {
        Branch branch = branchRepository.findByIdAndStoreId(branchId, storeId)
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
        if (branch.getStatus() != StoreStatus.ACTIVE) {
            throw ApiException.badRequest("This branch is inactive");
        }
        return branch;
    }

    private byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Upload an Excel file (.xlsx or .xls)");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw ApiException.badRequest("The file is larger than 10 MB. Split it into smaller files.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Unable to read the uploaded file");
        }
    }

    private String fileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "items.xlsx";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return truncate(name.trim(), 255);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private BigDecimal parseDecimal(String value, String label, List<String> errors) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", "").replace("₹", "").trim());
        } catch (NumberFormatException e) {
            errors.add(label + " must be a number");
            return null;
        }
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * Resolves every importable row to a stored unit code, creating Unit Master
     * records for units typed in the file that do not exist yet (once per
     * distinct code). Rows are rewritten to the canonical code so imported items
     * always reference the stored unit.
     */
    private int prepareUnits(List<ItemImportRow> rows, ImportContext context, Long storeId) {
        Map<String, String> resolved = new HashMap<>();
        int createdUnits = 0;
        for (ItemImportRow row : rows) {
            if (!importsRow(row)) {
                continue;
            }
            String existing = resolveUnit(row.getUnit(), context);
            if (existing != null) {
                row.setUnit(existing);
                continue;
            }
            if (row.getImportAction() != ImportAction.NEW_ITEM) {
                continue;
            }
            String typed = clean(row.getUnit());
            if (typed == null) {
                continue;
            }
            String code = UnitConverter.normalize(typed);
            String key = code.toLowerCase(Locale.ROOT);
            String canonical = resolved.get(key);
            if (canonical == null) {
                Optional<UnitMaster> stored = unitMasterRepository
                        .findByStoreIdAndUnitCodeIgnoreCase(storeId, code);
                if (stored.isPresent()) {
                    canonical = stored.get().getUnitCode();
                } else {
                    canonical = unitMasterService.create(new UnitMasterRequest(
                            code, code, null, StoreStatus.ACTIVE)).unitCode();
                    createdUnits++;
                }
                resolved.put(key, canonical);
            }
            row.setUnit(canonical);
        }
        return createdUnits;
    }

    private int reusedUnits(List<ItemImportRow> rows, ImportContext context) {
        Set<String> reused = new LinkedHashSet<>();
        for (ItemImportRow row : rows) {
            if (!importsRow(row)) {
                continue;
            }
            String unit = resolveUnit(row.getUnit(), context);
            if (unit != null) {
                reused.add(unit);
            }
        }
        return reused.size();
    }

    private UnitPlan unitPlan(List<ItemImportRow> rows, ImportContext context) {
        Set<String> newKeys = new LinkedHashSet<>();
        Set<String> reused = new LinkedHashSet<>();
        for (ItemImportRow row : rows) {
            if (!importsRow(row)) {
                continue;
            }
            String unit = resolveUnit(row.getUnit(), context);
            if (unit != null) {
                reused.add(unit);
            } else if (row.getImportAction() == ImportAction.NEW_ITEM && clean(row.getUnit()) != null) {
                newKeys.add(UnitConverter.normalize(clean(row.getUnit())).toLowerCase(Locale.ROOT));
            }
        }
        return new UnitPlan(newKeys.size(), reused.size());
    }

    private boolean importsRow(ItemImportRow row) {
        return row.getImportAction() == ImportAction.NEW_ITEM
                || row.getImportAction() == ImportAction.EXISTING_ITEM_OPENING_STOCK
                || row.getImportAction() == ImportAction.EXISTING_ITEM_NO_STOCK;
    }

    private String resolveUnit(String value, ImportContext context) {
        String unitKey = key(value);
        return unitKey == null ? null : context.unitsByKey().get(unitKey);
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String key(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String join(List<String> errors) {
        return String.join("; ", errors);
    }

    private String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private record Plan(ImportAction action, ImportValidationStatus status, String error, Item item) {
        static Plan invalid(String error) {
            return new Plan(ImportAction.VALIDATION_ERROR, ImportValidationStatus.INVALID, error, null);
        }

        static Plan duplicate(String error) {
            return new Plan(ImportAction.DUPLICATE_ROW, ImportValidationStatus.VALID, error, null);
        }
    }

    private record Counts(int newItems, int existingItems, int duplicateRows,
                          int invalidRows, int alreadyImportedRows) {
    }

    private record UnitPlan(int newUnits, int reusedUnits) {
    }

    private record ImportContext(
            Map<String, Category> categoriesByKey,
            Map<Long, Category> categoryById,
            Map<String, TaxMaster> taxesByKey,
            Map<String, String> unitsByKey,
            Map<String, Item> itemsByCode,
            Map<String, List<Item>> itemsByNameCategory,
            Set<Long> alreadyStocked
    ) {
    }
}
