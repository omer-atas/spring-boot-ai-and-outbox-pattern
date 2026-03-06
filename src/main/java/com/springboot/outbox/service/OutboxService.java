package com.springboot.outbox.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springboot.outbox.domain.entity.OutboxEvent;
import com.springboot.outbox.domain.enums.EventStatus;
import com.springboot.outbox.domain.enums.EventType;
import com.springboot.outbox.exception.OutboxException;
import com.springboot.outbox.repository.OutboxEventRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Outbox eventi oluştur - Business transaction ile aynı transaction içinde
     * REQUIRES_NEW kullanma! Ana transaction ile commit olmalı
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent createOutboxEvent(
            String aggregateId, EventType eventType, Object payload, String kafkaTopic) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            OutboxEvent event
                    = OutboxEvent.builder()
                            .aggregateId(aggregateId)
                            .eventType(eventType)
                            .payload(payloadJson)
                            .status(EventStatus.PENDING)
                            .kafkaTopic(kafkaTopic)
                            .kafkaKey(aggregateId)
                            .retryCount(0)
                            .maxRetry(3)
                            .createdAt(LocalDateTime.now())
                            .build();
            OutboxEvent savedEvent = outboxEventRepository.save(event);
            log.info(
                    "Outbox event created: eventId={}, aggregateId={}, eventType={}",
                    savedEvent.getId(),
                    aggregateId,
                    eventType);
            incrementCounter("outbox.events.created", eventType.name());
            return savedEvent;
        } catch (Exception e) {
            log.error(
                    "Failed to create outbox event: aggregateId={}, eventType={}", aggregateId, eventType, e);
            incrementCounter("outbox.events.creation.failed", eventType.name());
            throw new OutboxException("Failed to create outbox event", e);
        }
    }

    /**
     * Event'i PROCESSING olarak işaretle
     */
    @Transactional
    public void markAsProcessing(UUID eventId) {
        outboxEventRepository
                .findById(eventId)
                .ifPresent(
                        event -> {
                            event.setStatus(EventStatus.PROCESSING);
                            event.setUpdatedAt(LocalDateTime.now());
                            outboxEventRepository.save(event);
                            log.debug("Event marked as PROCESSING: eventId={}", eventId);
                        });
    }

    /**
     * Event'i PROCESSED olarak işaretle
     */
    @Transactional
    public void markAsProcessed(UUID eventId, Integer partition, Long offset) {
        outboxEventRepository
                .findById(eventId)
                .ifPresent(
                        event -> {
                            event.markAsProcessed();
                            event.setKafkaPartition(partition);
                            event.setKafkaOffset(offset);
                            outboxEventRepository.save(event);
                            log.info(
                                    "Event marked as PROCESSED: eventId={}, partition={}, offset={}",
                                    eventId,
                                    partition,
                                    offset);
                            incrementCounter("outbox.events.processed", event.getEventType().name());
                        });
    }

    /**
     * Event'i FAILED olarak işaretle
     */
    @Transactional
    public void markAsFailed(UUID eventId, String errorMessage) {
        outboxEventRepository
                .findById(eventId)
                .ifPresent(
                        event -> {
                            event.markAsFailed(errorMessage);
                            // Max retry aşıldıysa DEAD_LETTER'a taşı
                            if (!event.canRetry()) {
                                event.markAsDeadLetter();
                                log.error(
                                        "Event moved to DEAD_LETTER: eventId={}, retryCount={}",
                                        eventId,
                                        event.getRetryCount());
                                incrementCounter("outbox.events.dead_letter", event.getEventType().name());
                            }
                            outboxEventRepository.save(event);
                            log.warn(
                                    "Event marked as FAILED: eventId={}, retryCount={}/{}, error={}",
                                    eventId,
                                    event.getRetryCount(),
                                    event.getMaxRetry(),
                                    errorMessage);
                            incrementCounter("outbox.events.failed", event.getEventType().name());
                        });
    }

    /**
     * Stuck event detection - belirli bir süredir PROCESSING durumunda olan
     * eventleri FAILED yap
     */
    @Transactional
    public int handleStuckEvents(int minutesThreshold) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(minutesThreshold);
        int count = outboxEventRepository.markStuckEventsAsFailed(threshold);
        if (count > 0) {
            log.warn("Marked {} stuck events as FAILED (threshold: {} minutes)", count, minutesThreshold);
            meterRegistry.counter("outbox.events.stuck").increment(count);
        }
        return count;
    }

    private void incrementCounter(String counterName, String eventType) {
        Counter.builder(counterName).tag("event_type", eventType).register(meterRegistry).increment();
    }
}
