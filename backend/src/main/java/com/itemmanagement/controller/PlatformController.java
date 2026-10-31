package com.itemmanagement.controller;

import com.itemmanagement.dto.subscription.PlatformContactRequest;
import com.itemmanagement.dto.subscription.PlatformContactResponse;
import com.itemmanagement.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformController {

    private final SubscriptionService subscriptionService;

    @GetMapping("/contact")
    @PreAuthorize("hasAuthority('PLATFORM_MANAGE')")
    public PlatformContactResponse contact() {
        return subscriptionService.contact();
    }

    @PutMapping("/contact")
    @PreAuthorize("hasAuthority('PLATFORM_MANAGE')")
    public PlatformContactResponse updateContact(@Valid @RequestBody PlatformContactRequest request) {
        return subscriptionService.updateContact(request);
    }
}
