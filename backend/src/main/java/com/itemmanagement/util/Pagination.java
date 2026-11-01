package com.itemmanagement.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

public final class Pagination {

    public static final int DEFAULT_MAX_SIZE = 100;

    private Pagination() {
    }

    public static Pageable of(int page, int size) {
        return of(page, size, DEFAULT_MAX_SIZE);
    }

    public static Pageable of(int page, int size, int maxSize) {
        return PageRequest.of(clampPage(page), clampSize(size, maxSize));
    }

    public static int clampPage(int page) {
        return Math.max(page, 0);
    }

    public static int clampSize(int size, int maxSize) {
        return Math.min(Math.max(size, 1), maxSize);
    }
}
