package com.itemmanagement.dto.imports;

public record ImportSummary(
        int totalRows,
        int newCategories,
        int newUnits,
        int reusedUnits,
        int newItems,
        int existingItems,
        int stockUpdates,
        int duplicateRows,
        int invalidRows,
        int alreadyImportedRows,
        int importableRows
) {
}
