package com.shijie.resumehelper.interview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Periodically removes a bounded number of expired interview snapshots. */
@Component
public final class InterviewStoreMaintenance {
    private static final Logger log = LoggerFactory.getLogger(InterviewStoreMaintenance.class);
    private final InterviewStore store;
    private final Clock clock;
    private final int batchSize;

    public InterviewStoreMaintenance(InterviewStore store, Clock clock,
            @Value("${app.interview.cleanup-batch-size:100}") int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("cleanup batch size must be positive");
        this.store = store;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.interview.cleanup-interval-ms:60000}")
    public void cleanupExpired() {
        try {
            int removed = store.deleteExpired(clock.instant(), batchSize);
            if (removed > 0) log.info("Removed {} expired interview snapshots", removed);
        } catch (RuntimeException failure) {
            log.warn("Expired interview snapshot cleanup failed", failure);
        }
    }
}
