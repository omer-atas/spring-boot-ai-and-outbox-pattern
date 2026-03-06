package com.springboot.outbox.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.springboot.outbox.domain.entity.OutboxEvent;
import com.springboot.outbox.domain.enums.EventStatus;
import com.springboot.outbox.repository.OutboxEventRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxCleanupService {

    private final OutboxEventRepository outboxEventRepository;
    private final MeterRegistry meterRegistry;

    @Value("${outbox.cleanup.retention-days:7}")
    private int retentionDays;

    @Value("${outbox.cleanup.batch-size:1000}")
    private int batchSize;

    /**
     * PROCESSED durumundaki eski eventleri temizle
     */
    @Transactional
    public int cleanupProcessedEvents() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
        log.info(
                "Cleaning up processed events older than {} (retention: {} days)",
                cutoffDate,
                retentionDays);
        int totalDeleted = 0;
        int deleted;
        do {
            deleted
                    = outboxEventRepository.deleteProcessedEventsBefore(EventStatus.PROCESSED, cutoffDate);
            totalDeleted += deleted;
            if (deleted > 0) {
                log.debug("Deleted {} processed events in batch", deleted);
            }
        } while (deleted >= batchSize);
        if (totalDeleted > 0) {
            log.info("Cleanup completed: deleted {} processed events", totalDeleted);
            meterRegistry.counter("outbox.cleanup.processed").increment(totalDeleted);
        } else {
            log.debug("No processed events to cleanup");
        }
        return totalDeleted;
    }

    /**
     * DEAD_LETTER eventleri arşivle (başka bir tabloya taşı veya S3'e export
     * et)
     */
    @Transactional
    public int archiveDeadLetterEvents() {
        List<OutboxEvent> deadLetterEvents
                = outboxEventRepository.findDeadLetterEvents(PageRequest.of(0, batchSize));
        if (deadLetterEvents.isEmpty()) {
            log.debug("No dead letter events to archive");
            return 0;
        }
        log.info("Archiving {} dead letter events", deadLetterEvents.size());
        // Archive işlemi (örnek: başka bir service'e gönder, S3'e yaz, vb.)
        deadLetterEvents.forEach(
                event -> {
                    try {
                        // Archive logic burada
                        log.debug(
                                "Archived dead letter event: eventId={}, aggregateId={}",
                                event.getId(),
                                event.getAggregateId());
                    } catch (Exception e) {
                        log.error("Failed to archive event: eventId={}", event.getId(), e);
                    }
                });
        meterRegistry.counter("outbox.cleanup.dead_letter").increment(deadLetterEvents.size());
        return deadLetterEvents.size();
    }

    /**
     * Outbox tablosunun boyutunu ve istatistiklerini logla
     */
    public void logOutboxStatistics() {
        long pendingCount = outboxEventRepository.countByStatus(EventStatus.PENDING);
        long processingCount = outboxEventRepository.countByStatus(EventStatus.PROCESSING);
        long processedCount = outboxEventRepository.countByStatus(EventStatus.PROCESSED);
        long failedCount = outboxEventRepository.countByStatus(EventStatus.FAILED);
        long deadLetterCount = outboxEventRepository.countByStatus(EventStatus.DEAD_LETTER);
        log.info(
                "Outbox statistics - PENDING: {}, PROCESSING: {}, PROCESSED: {}, FAILED: {}, DEAD_LETTER:"
                + " {}",
                pendingCount,
                processingCount,
                processedCount,
                failedCount,
                deadLetterCount);
        // Metrics'e yaz
        meterRegistry.gauge("outbox.events.pending", pendingCount);
        meterRegistry.gauge("outbox.events.processing", processingCount);
        meterRegistry.gauge("outbox.events.processed", processedCount);
        meterRegistry.gauge("outbox.events.failed", failedCount);
        meterRegistry.gauge("outbox.events.dead_letter", deadLetterCount);
    }
}
