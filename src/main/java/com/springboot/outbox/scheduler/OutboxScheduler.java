package com.springboot.outbox.scheduler;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.springboot.outbox.service.OutboxPublisher;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "outbox.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxScheduler {

    private final OutboxPublisher outboxPublisher;

    /**
     * PENDING eventleri her 5 saniyede bir işle ShedLock ile sadece bir
     * instance çalışır (distributed lock)
     */
    @Scheduled(
            fixedDelayString = "${outbox.scheduler.pending.fixed-delay:5000}",
            initialDelayString = "${outbox.scheduler.pending.initial-delay:10000}")
    @SchedulerLock(name = "processePendingEvents", lockAtMostFor = "4m", lockAtLeastFor = "30s")
    @Timed(value = "outbox.scheduler.pending", description = "Time to process pending events")
    public void processPendingEvents() {
        Instant start = Instant.now();
        log.debug("Starting pending events processing");
        try {
            outboxPublisher.processPendingEvents();
            Duration duration = Duration.between(start, Instant.now());
            log.debug("Completed pending events processing in {}ms", duration.toMillis());
        } catch (Exception e) {
            log.error("Error processing pending events", e);
        }
    }

    /**
     * FAILED eventleri her 1 dakikada bir retry et
     */
    @Scheduled(
            fixedDelayString = "${outbox.scheduler.retry.fixed-delay:60000}",
            initialDelayString = "${outbox.scheduler.retry.initial-delay:30000}")
    @SchedulerLock(name = "retryFailedEvents", lockAtMostFor = "4m", lockAtLeastFor = "30s")
    @Timed(value = "outbox.scheduler.retry", description = "Time to retry failed events")
    public void retryFailedEvents() {
        Instant start = Instant.now();
        log.debug("Starting failed events retry");
        try {
            outboxPublisher.retryFailedEvents();
            Duration duration = Duration.between(start, Instant.now());
            log.debug("Completed failed events retry in {}ms", duration.toMillis());
        } catch (Exception e) {
            log.error("Error retrying failed events", e);
        }
    }

    /**
     * Stuck event detection - her 10 dakikada bir
     */
    @Scheduled(
            fixedDelayString = "${outbox.scheduler.stuck.fixed-delay:600000}",
            initialDelayString = "${outbox.scheduler.stuck.initial-delay:60000}")
    @SchedulerLock(name = "detectStuckEvents", lockAtMostFor = "5m", lockAtLeastFor = "1m")
    @Timed(value = "outbox.scheduler.stuck", description = "Time to detect stuck events")
    public void detectStuckEvents() {
        Instant start = Instant.now();
        log.debug("Starting stuck events detection");
        try {
            outboxPublisher.detectAndHandleStuckEvents();
            Duration duration = Duration.between(start, Instant.now());
            log.debug("Completed stuck events detection in {}ms", duration.toMillis());
        } catch (Exception e) {
            log.error("Error detecting stuck events", e);
        }
    }
}
