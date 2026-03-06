package com.springboot.outbox.scheduler;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.springboot.outbox.service.OutboxCleanupService;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "outbox.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class CleanupScheduler {

    private final OutboxCleanupService cleanupService;

    /**
     * PROCESSED eventleri her gün gece 02:00'de temizle
     */
    @Scheduled(cron = "${outbox.cleanup.cron:0 0 2 * * ?}")
    @SchedulerLock(name = "cleanupProcessedEvents", lockAtMostFor = "1h", lockAtLeastFor = "30m")
    @Timed(value = "outbox.cleanup.processed", description = "Time to cleanup processed events")
    public void cleanupProcessedEvents() {
        Instant start = Instant.now();
        log.info("Starting processed events cleanup");
        try {
            int deletedCount = cleanupService.cleanupProcessedEvents();
            Duration duration = Duration.between(start, Instant.now());
            log.info(
                    "Completed processed events cleanup: deleted={}, duration={}ms",
                    deletedCount,
                    duration.toMillis());
        } catch (Exception e) {
            log.error("Error cleaning up processed events", e);
        }
    }

    /**
     * DEAD_LETTER eventleri arşivle
     */
    @Scheduled(cron = "${outbox.cleanup.dead-letter.cron:0 0 3 * * ?}")
    @SchedulerLock(name = "archiveDeadLetterEvents", lockAtMostFor = "1h", lockAtLeastFor = "30m")
    @Timed(value = "outbox.cleanup.dead_letter", description = "Time to archive dead letter events")
    public void archiveDeadLetterEvents() {
        Instant start = Instant.now();
        log.info("Starting dead letter events archiving");
        try {
            int archivedCount = cleanupService.archiveDeadLetterEvents();
            Duration duration = Duration.between(start, Instant.now());
            log.info(
                    "Completed dead letter events archiving: archived={}, duration={}ms",
                    archivedCount,
                    duration.toMillis());
        } catch (Exception e) {
            log.error("Error archiving dead letter events", e);
        }
    }
}
