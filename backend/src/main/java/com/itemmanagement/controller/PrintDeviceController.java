package com.itemmanagement.controller;

import com.itemmanagement.dto.printing.DeviceConfigResponse;
import com.itemmanagement.dto.printing.DevicePairRequest;
import com.itemmanagement.dto.printing.DevicePairResponse;
import com.itemmanagement.service.PrintDeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints called by the native print bridges. They are intentionally
 * outside JWT authentication: pairing is authorised by a one-time code,
 * and configuration pulls by an opaque device token issued at pairing.
 */
@RestController
@RequestMapping("/api/printing")
@RequiredArgsConstructor
public class PrintDeviceController {

    public static final String DEVICE_TOKEN_HEADER = "X-Device-Token";

    private final PrintDeviceService deviceService;

    @PostMapping("/devices/pair")
    public DevicePairResponse pair(@Valid @RequestBody DevicePairRequest request) {
        return deviceService.pair(request);
    }

    @GetMapping("/device/config")
    public DeviceConfigResponse config(@RequestHeader(name = DEVICE_TOKEN_HEADER, required = false) String token) {
        return deviceService.config(token);
    }
}
