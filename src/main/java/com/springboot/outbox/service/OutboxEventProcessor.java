package com.springboot.outbox.service;

import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.springboot.outbox.domain.entity.OutboxEvent;
import com.springboot.outbox.kafka.KafkaProducer;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Helper service to bridge the proxy gap for resilience and transactionality.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventProcessor {

    private final OutboxService outboxService;
    private final KafkaProducer kafkaProducer;
    private final MeterRegistry meterRegistry;

    @CircuitBreaker(name = "kafkaPublisher", fallbackMethod = "publishFallback")
    @Retry(name = "kafkaPublisher")
    @Transactional
    public void processEvent(OutboxEvent event) {
        outboxService.markAsProcessing(event.getId());

        CompletableFuture<KafkaProducer.PublishResult> future
                = kafkaProducer.sendEvent(
                        event.getKafkaTopic(),
                        event.getKafkaKey(),
                        event.getPayload(),
                        event.getId().toString());

        future.whenComplete(
                (result, throwable) -> {
                    if (throwable != null) {
                        handlePublishError(event, throwable);
                    } else {
                        handlePublishSuccess(event, result);
                    }
                });
    }

    @Transactional
    public void handlePublishSuccess(OutboxEvent event, KafkaProducer.PublishResult result) {
        outboxService.markAsProcessed(event.getId(), result.getPartition(), result.getOffset());

        log.info(
                "Successfully published event: eventId={}, aggregateId={}, topic={}, partition={},"
                + " offset={}",
                event.getId(),
                event.getAggregateId(),
                event.getKafkaTopic(),
                result.getPartition(),
                result.getOffset());

        meterRegistry
                .counter(
                        "outbox.publisher.success",
                        "event_type",
                        event.getEventType().name(),
                        "topic",
                        event.getKafkaTopic())
                .increment();
    }

    @Transactional
    public void handlePublishError(OutboxEvent event, Throwable throwable) {
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

    public void publishFallback(OutboxEvent event, Exception e) {
        log.error("Circuit breaker opened for event publishing: eventId={}", event.getId(), e);
        handlePublishError(event, e);
    }
}
