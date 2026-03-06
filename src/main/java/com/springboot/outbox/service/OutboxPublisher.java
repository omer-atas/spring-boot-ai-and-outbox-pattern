package com.springboot.outbox.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.springboot.outbox.domain.entity.OutboxEvent;
import com.springboot.outbox.domain.enums.EventStatus;
import com.springboot.outbox.kafka.KafkaProducer;
import com.springboot.outbox.repository.OutboxEventRepository;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxService outboxService;
    private final KafkaProducer kafkaProducer;
    private final MeterRegistry meterRegistry;
    private static final int BATCH_SIZE = 100;
    private static final int PROCESSING_DELAY_SECONDS = 5;

    /**
     * PENDING durumundaki eventleri işle ve Kafka'ya gönder
     */
    @Timed(value = "outbox.publisher.process.pending", description = "Time to process pending events")
    @Transactional
    public void processPendingEvents() {
        LocalDateTime beforeTime = LocalDateTime.now().minusSeconds(PROCESSING_DELAY_SECONDS);
        List<OutboxEvent> pendingEvents
                = outboxEventRepository.findPendingEventsForProcessing(
                        EventStatus.PENDING, beforeTime, PageRequest.of(0, BATCH_SIZE));
        if (pendingEvents.isEmpty()) {
            log.debug("No pending events found to process");
            return;
        }
        log.info("Processing {} pending outbox events", pendingEvents.size());
        pendingEvents.forEach(this::publishEventAsync);
    }

    /**
     * FAILED durumundaki eventleri retry et
     */
    @Timed(value = "outbox.publisher.retry.failed", description = "Time to retry failed events")
    @Transactional
    public void retryFailedEvents() {
        // Son 5 dakikada güncellenen failed eventleri al
        LocalDateTime retryAfter = LocalDateTime.now().minusMinutes(5);
        List<OutboxEvent> failedEvents
                = outboxEventRepository.findFailedEventsForRetry(
                        EventStatus.FAILED, retryAfter, PageRequest.of(0, BATCH_SIZE));
        if (failedEvents.isEmpty()) {
            log.debug("No failed events found to retry");
            return;
        }
        log.info("Retrying {} failed outbox events", failedEvents.size());
        failedEvents.forEach(this::publishEventAsync);
    }

    /**
     * Event'i asenkron olarak Kafka'ya gönder
     */
    @CircuitBreaker(name = "kafkaPublisher", fallbackMethod = "publishFallback")
    @Retry(name = "kafkaPublisher")
    private void publishEventAsync(OutboxEvent event) {
        try {
            // Event'i PROCESSING olarak işaretle
            outboxService.markAsProcessing(event.getId());
            // Kafka'ya gönder
            CompletableFuture<KafkaProducer.PublishResult> future
                    = kafkaProducer.sendEvent(
                            event.getKafkaTopic(),
                            event.getKafkaKey(),
                            event.getPayload(),
                            event.getId().toString());
            // Callback ile sonucu işle
            future.whenComplete(
                    (result, throwable) -> {
                        if (throwable != null) {
                            handlePublishError(event, throwable);
                        } else {
                            handlePublishSuccess(event);
                        }
                    });
        } catch (Exception e) {
            handlePublishError(event, e);
        }
    }

    /**
     * Başarılı publish sonrası işlemler
     */
    @Transactional
    protected void handlePublishSuccess(OutboxEvent event) {
        try {
            // Kafka metadata'yı al (partition, offset)
            Integer partition = kafkaProducer.getLastPartition();
            Long offset = kafkaProducer.getLastOffset();
            outboxService.markAsProcessed(event.getId(), partition, offset);
            log.info(
                    "Successfully published event: eventId={}, aggregateId={}, topic={}, partition={},"
                    + " offset={}",
                    event.getId(),
                    event.getAggregateId(),
                    event.getKafkaTopic(),
                    partition,
                    offset);
            meterRegistry
                    .counter(
                            "outbox.publisher.success",
                            "event_type",
                            event.getEventType().name(),
                            "topic",
                            event.getKafkaTopic())
                    .increment();
        } catch (Exception e) {
            log.error("Error marking event as processed: eventId={}", event.getId(), e);
        }
    }

    /**
     * Başarısız publish sonrası işlemler
     */
    @Transactional
    protected void handlePublishError(OutboxEvent event, Throwable throwable) {
        String errorMessage = throwable.getMessage();
        log.error(
                "Failed to publish event: eventId={}, aggregateId={}, error={}",
                event.getId(),
                event.getAggregateId(),
                errorMessage,
                throwable);
        outboxService.markAsFailed(event.getId(), errorMessage);
        meterRegistry
                .counter(
                        "outbox.publisher.failed",
                        "event_type",
                        event.getEventType().name(),
                        "topic",
                        event.getKafkaTopic())
                .increment();
    }

    /**
     * Circuit breaker fallback
     */
    private void publishFallback(OutboxEvent event, Exception e) {
        log.error("Circuit breaker opened for event publishing: eventId={}", event.getId(), e);
        handlePublishError(event, e);
    }

    /**
     * Stuck event detection
     */
    @Timed(value = "outbox.publisher.stuck.detection", description = "Time to detect stuck events")
    public void detectAndHandleStuckEvents() {
        int stuckCount = outboxService.handleStuckEvents(30); // 30 dakika threshold
        if (stuckCount > 0) {
            log.warn("Detected and handled {} stuck events", stuckCount);
        }
    }
}
