package com.itemmanagement.dto.imports;

public record ImportSummary(
        int totalRows,
        int newCategories,
        int newItems,
        int existingItems,
        int stockUpdates,
        int duplicateRows,
        int invalidRows,
        int alreadyImportedRows,
        int importableRows
) {
}
