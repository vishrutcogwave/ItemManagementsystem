package com.itemmanagement.dto.printing;

import java.time.Instant;
import java.util.List;

public record DeviceConfigResponse(
        Long deviceId,
        String deviceName,
        String platform,
        Long storeId,
        String storeName,
        Long branchId,
        String branchName,
        List<PrinterConfigResponse> printers,
        Instant syncedAt
) {
}
