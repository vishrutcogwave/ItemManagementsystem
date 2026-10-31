package com.itemmanagement.service;

import com.itemmanagement.entity.PrintJob;
import com.itemmanagement.repository.PrintJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts a print job in its own transaction. A concurrent automatic print
 * for the same receipt loses the unique-key race and must not abort the
 * caller's transaction, so the insert is isolated here and the caller simply
 * falls back to the winning job.
 */
@Component
@RequiredArgsConstructor
public class PrintJobWriter {

    private final PrintJobRepository jobRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PrintJob insert(PrintJob job) {
        return jobRepository.save(job);
    }
}
