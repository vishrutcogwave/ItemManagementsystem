package com.itemmanagement.service;

import com.itemmanagement.entity.Category;
import com.itemmanagement.entity.TaxMaster;
import com.itemmanagement.entity.UnitMaster;
import com.itemmanagement.exception.ApiException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads and writes the bulk item import workbook. The parser only extracts
 * raw cell values; all business validation lives in the import service so the
 * preview and the confirmation use exactly the same rules.
 */
@Service
public class ItemImportExcelService {

    public static final List<String> HEADERS = List.of(
            "Item Code", "Item Name", "Category Name", "Unit", "Rate", "Tax Name",
            "Opening Stock Quantity", "Minimum Stock Quantity", "Description");

    private static final List<String> REQUIRED_HEADERS = List.of(
            "item name", "category name", "unit", "rate", "tax name");

    private static final int MAX_ROWS = 2000;

    public ParsedWorkbook parse(byte[] content) {
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("Upload an Excel file (.xlsx or .xls)");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw ApiException.badRequest("The Excel workbook does not contain any sheet");
            }
            Sheet sheet = workbook.getSheet("Items");
            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw ApiException.badRequest("The Excel sheet is empty. Download the template and add your items.");
            }
            Map<String, Integer> columns = readHeader(headerRow);
            List<String> missing = REQUIRED_HEADERS.stream()
                    .filter(header -> !columns.containsKey(header))
                    .map(this::displayHeader)
                    .toList();
            if (!missing.isEmpty()) {
                throw ApiException.badRequest("The Excel file is missing required columns: "
                        + String.join(", ", missing)
                        + ". Download the template and keep the column names unchanged.");
            }

            List<ParsedRow> rows = new ArrayList<>();
            for (int r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                ParsedRow parsed = new ParsedRow(
                        r + 1,
                        value(row, columns.get("item code")),
                        value(row, columns.get("item name")),
                        value(row, columns.get("category name")),
                        value(row, columns.get("unit")),
                        value(row, columns.get("rate")),
                        value(row, columns.get("tax name")),
                        value(row, columns.get("opening stock quantity")),
                        value(row, columns.get("minimum stock quantity")),
                        value(row, columns.get("description")));
                if (parsed.blank()) {
                    continue;
                }
                rows.add(parsed);
                if (rows.size() > MAX_ROWS) {
                    throw ApiException.badRequest("The file contains more than "
                            + MAX_ROWS + " item rows. Split it into smaller files.");
                }
            }
            if (rows.isEmpty()) {
                throw ApiException.badRequest("The uploaded file does not contain any item rows. "
                        + "Download the template, add items and upload it again.");
            }
            return new ParsedWorkbook(rows);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("The uploaded file is not a valid Excel workbook. "
                    + "Download the template and upload a .xlsx or .xls file.");
        }
    }

    public byte[] template(List<Category> categories, List<TaxMaster> taxes,
                           List<UnitMaster> unitMaster, String branchName) {
        List<String> unitCodes = unitMaster.stream().map(UnitMaster::getUnitCode).toList();
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet items = workbook.createSheet("Items");

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            Row header = items.createRow(0);
            header.setHeightInPoints(22);
            for (int i = 0; i < HEADERS.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS.get(i));
                cell.setCellStyle(headerStyle);
            }
            int[] widths = {16, 26, 22, 12, 12, 20, 24, 24, 40};
            for (int i = 0; i < widths.length; i++) {
                items.setColumnWidth(i, widths[i] * 256);
            }
            items.createFreezePane(0, 1);

            DataValidationHelper helper = items.getDataValidationHelper();
            if (!unitCodes.isEmpty()) {
                Sheet unitSheet = workbook.createSheet("Units");
                for (int i = 0; i < unitCodes.size(); i++) {
                    unitSheet.createRow(i).createCell(0).setCellValue(unitCodes.get(i));
                }
                Name unitRange = workbook.createName();
                unitRange.setNameName("UnitCodes");
                unitRange.setRefersToFormula("Units!$A$1:$A$" + unitCodes.size());
                DataValidationConstraint units = helper.createFormulaListConstraint("UnitCodes");
                DataValidation unitValidation = helper.createValidation(units,
                        new CellRangeAddressList(1, MAX_ROWS, 3, 3));
                unitValidation.setSuppressDropDownArrow(true);
                unitValidation.setShowErrorBox(true);
                unitValidation.setErrorStyle(DataValidation.ErrorStyle.WARNING);
                unitValidation.createErrorBox("Unit not in Unit Master",
                        "Continue and this unit will be created automatically when the file is imported. "
                                + "Unit codes allow 1-20 letters, numbers, dot, underscore or hyphen.");
                items.addValidationData(unitValidation);
            }

            writeInstructions(workbook, categories, taxes, unitCodes, branchName);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to build the import template", e);
        }
    }

    private void writeInstructions(Workbook workbook, List<Category> categories,
                                   List<TaxMaster> taxes, List<String> unitCodes, String branchName) {
        Sheet sheet = workbook.createSheet("Instructions");
        sheet.setColumnWidth(0, 110 * 256);

        CellStyle titleStyle = workbook.createCellStyle();
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleStyle.setFont(titleFont);

        CellStyle labelStyle = workbook.createCellStyle();
        Font bold = workbook.createFont();
        bold.setBold(true);
        labelStyle.setFont(bold);

        int rowIndex = 0;
        Cell title = sheet.createRow(rowIndex++).createCell(0);
        title.setCellValue("Bulk item import instructions");
        title.setCellStyle(titleStyle);
        rowIndex++;

        rowIndex = instruction(sheet, rowIndex, "Opening stock branch", branchName, labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Sheet to fill",
                "Use the 'Items' sheet. Do not rename or move the column headers.", labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Required columns",
                "Item Name, Category Name, Unit, Rate, Tax Name", labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Optional columns",
                "Item Code, Opening Stock Quantity, Minimum Stock Quantity, Description", labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Item Code",
                "Leave blank to let the system generate a code. When supplied, an existing code "
                        + "is treated as an existing item and its master fields are not overwritten.", labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Units",
                (unitCodes.isEmpty() ? "No units yet. Any code typed in the Unit column is created automatically."
                        : "Existing: " + String.join(", ", unitCodes)
                        + ". Any other code is created automatically.")
                        + " Unit codes allow 1-20 letters, numbers, dot, underscore or hyphen.", labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Existing categories",
                categories.isEmpty() ? "(none yet)" : categories.stream()
                        .map(Category::getCategoryName).collect(Collectors.joining(", ")), labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Available taxes",
                taxes.isEmpty() ? "(none yet)" : taxes.stream()
                        .map(tax -> tax.getTaxName() + " (" + tax.getGstPercentage().stripTrailingZeros().toPlainString() + "%)")
                        .collect(Collectors.joining(", ")), labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Category matching",
                "Categories are matched to the selected store ignoring case and spaces. "
                        + "Missing categories are created once; existing categories are reused.", labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Opening stock",
                "Recorded once per item and branch as an OPENING_STOCK inventory transaction. "
                        + "Rows for items that already have stock or stock history are skipped as Already Imported.", labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Minimum stock",
                "Defaults to 5 when left blank.", labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Duplicates",
                "Rows that repeat an item code, or an item name and category, are rejected.", labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Repeat uploads",
                "The same file cannot be imported twice for the same branch.", labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Invalid rows",
                "The whole file is blocked while any row has a validation error or duplicate. "
                        + "Correct the file and upload it again.", labelStyle);
        rowIndex = instruction(sheet, rowIndex, "Example row",
                "MILK-1L | Milk | Dairy | LITER | 60.00 | GST 0% (Exempt) | 40 | 10 | Fresh milk", labelStyle);
    }

    private int instruction(Sheet sheet, int rowIndex, String label, String value, CellStyle labelStyle) {
        Row row = sheet.createRow(rowIndex);
        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(labelStyle);
        row.createCell(1).setCellValue(value);
        return rowIndex + 1;
    }

    private Map<String, Integer> readHeader(Row headerRow) {
        Map<String, Integer> columns = new HashMap<>();
        for (int c = headerRow.getFirstCellNum(); c < headerRow.getLastCellNum(); c++) {
            String header = normalizeHeader(value(headerRow, c));
            if (!header.isEmpty()) {
                columns.putIfAbsent(header, c);
            }
        }
        return columns;
    }

    private String normalizeHeader(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replace("*", "").trim();
    }

    private String displayHeader(String normalized) {
        return HEADERS.stream()
                .filter(header -> normalizeHeader(header).equals(normalized))
                .findFirst()
                .orElse(normalized);
    }

    private String value(Row row, Integer column) {
        if (column == null) {
            return null;
        }
        Cell cell = row.getCell(column);
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        String value = switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                yield BigDecimal.valueOf(cell.getNumericCellValue())
                        .stripTrailingZeros().toPlainString();
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record ParsedRow(
            int rowNumber,
            String itemCode,
            String itemName,
            String categoryName,
            String unit,
            String rate,
            String taxName,
            String openingStock,
            String minimumStock,
            String description
    ) {
        boolean blank() {
            return itemCode == null && itemName == null && categoryName == null
                    && unit == null && rate == null && taxName == null
                    && openingStock == null && minimumStock == null && description == null;
        }
    }

    public record ParsedWorkbook(List<ParsedRow> rows) {
    }
}
