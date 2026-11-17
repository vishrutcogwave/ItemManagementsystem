package com.itemmanagement.controller;

import com.itemmanagement.dto.subscription.PlatformContactRequest;
import com.itemmanagement.dto.subscription.PlatformContactResponse;
import com.itemmanagement.security.PlatformPolicy;
import com.itemmanagement.service.BuildInfoService;
import com.itemmanagement.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformController {

    private final SubscriptionService subscriptionService;
    private final BuildInfoService buildInfoService;

    /**
     * Deployment verification detail (build time and git commit). Restricted to
     * platform administrators because the exact revision is useful only to the
     * deployment closeout and would otherwise help fingerprint the server.
     */
    @GetMapping("/version")
    @PreAuthorize(PlatformPolicy.PLATFORM_ADMIN_ONLY)
    public ResponseEntity<Map<String, Object>> version() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", buildInfoService.name());
        body.put("version", buildInfoService.version());
        body.put("buildTime", buildInfoService.buildTime());
        body.put("gitCommit", buildInfoService.gitCommit());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /**
     * Public read for the sign-up contact screen. Only the configured platform
     * contact email/phone is exposed; editing stays behind PLATFORM_MANAGE.
     */
    @GetMapping("/public-contact")
    public PlatformContactResponse publicContact() {
        return subscriptionService.contact();
    }

    @GetMapping("/contact")
    @PreAuthorize(PlatformPolicy.PLATFORM_ADMIN_ONLY)
    public PlatformContactResponse contact() {
        return subscriptionService.contact();
    }

    @PutMapping("/contact")
    @PreAuthorize(PlatformPolicy.PLATFORM_ADMIN_ONLY)
    public PlatformContactResponse updateContact(@Valid @RequestBody PlatformContactRequest request) {
        return subscriptionService.updateContact(request);
    }
}
