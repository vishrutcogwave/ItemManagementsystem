package com.itemmanagement.dto.printing;

import java.util.List;

public record DevicePairResponse(
        Long deviceId,
        String deviceToken,
        String deviceName,
        String platform,
        Long storeId,
        String storeName,
        Long branchId,
        String branchName,
        List<PrinterConfigResponse> printers
) {
}
