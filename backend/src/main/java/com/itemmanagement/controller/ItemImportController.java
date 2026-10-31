package com.itemmanagement.controller;

import com.itemmanagement.dto.imports.ImportBatchResponse;
import com.itemmanagement.dto.imports.ImportPreviewResponse;
import com.itemmanagement.service.ItemImportService;
import com.itemmanagement.util.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/item-imports")
@RequiredArgsConstructor
public class ItemImportController {

    private final ItemImportService itemImportService;

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('ITEM_VIEW')")
    public ResponseEntity<byte[]> template() {
        byte[] content = itemImportService.template();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("item-import-template.xlsx").build().toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ITEM_MANAGE') and hasAuthority('INVENTORY_ADJUST')")
    public ImportPreviewResponse preview(@RequestParam("file") MultipartFile file,
                                         @RequestParam("branchId") Long branchId) {
        return itemImportService.preview(file, branchId);
    }

    @PostMapping("/{batchId}/confirm")
    @PreAuthorize("hasAuthority('ITEM_MANAGE') and hasAuthority('INVENTORY_ADJUST')")
    public ImportPreviewResponse confirm(@PathVariable Long batchId) {
        return itemImportService.confirm(batchId);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ITEM_VIEW') or hasAuthority('INVENTORY_VIEW')")
    public PageResponse<ImportBatchResponse> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return itemImportService.history(page, size);
    }

    @GetMapping("/{batchId}")
    @PreAuthorize("hasAuthority('ITEM_VIEW') or hasAuthority('INVENTORY_VIEW')")
    public ImportPreviewResponse detail(@PathVariable Long batchId) {
        return itemImportService.detail(batchId);
    }
}
