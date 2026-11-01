package com.itemmanagement.controller;

import com.itemmanagement.dto.common.StatusRequest;
import com.itemmanagement.dto.platform.CredentialHistoryResponse;
import com.itemmanagement.dto.platform.PlatformAccountRequests.ResetPasswordRequest;
import com.itemmanagement.dto.platform.PlatformAccountRequests.UsernameUpdateRequest;
import com.itemmanagement.dto.platform.PlatformAccountResponse;
import com.itemmanagement.dto.platform.PlatformPasswordResetResponse;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.service.PlatformAccountService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/platform/accounts")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PLATFORM_MANAGE')")
public class PlatformAccountController {

    private final PlatformAccountService accountService;

    @GetMapping
    public PageResponse<PlatformAccountResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) StoreStatus status,
            @RequestParam(required = false) Long storeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return accountService.list(search, status, storeId, page, size);
    }

    @GetMapping("/{id}")
    public PlatformAccountResponse get(@PathVariable Long id) {
        return accountService.get(id);
    }

    @PutMapping("/{id}/username")
    public PlatformAccountResponse updateUsername(@PathVariable Long id,
                                                  @Valid @RequestBody UsernameUpdateRequest request) {
        return accountService.updateUsername(id, request);
    }

    @PutMapping("/{id}/status")
    public PlatformAccountResponse updateStatus(@PathVariable Long id,
                                                @Valid @RequestBody StatusRequest request) {
        return accountService.updateStatus(id, request.status());
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<PlatformPasswordResetResponse> resetPassword(
            @PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(accountService.resetPassword(id, request));
    }

    @GetMapping("/{id}/history")
    public List<CredentialHistoryResponse> history(@PathVariable Long id) {
        return accountService.history(id);
    }
}
