package com.itemmanagement.controller;

import com.itemmanagement.dto.subscription.SubscriptionSummaryResponse;
import com.itemmanagement.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    /**
     * Available to every authenticated store user, including when the subscription
     * has expired, so the expiry screen can show dates, limits and platform contact.
     */
    @GetMapping("/summary")
    public SubscriptionSummaryResponse summary() {
        return subscriptionService.currentStoreSummary();
    }
}
