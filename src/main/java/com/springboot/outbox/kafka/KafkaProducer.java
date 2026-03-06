package com.springboot.outbox.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    @Getter
    private Integer lastPartition;
    @Getter
    private Long lastOffset;

    /**
     * Event'i Kafka'ya gönder
     */
    public CompletableFuture<PublishResult> sendEvent(
            String topic, String key, String payload, String eventId) {
        long startTime = System.currentTimeMillis();
        ProducerRecord<String, String> record = createProducerRecord(topic, key, payload, eventId);
        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(record);
        return future.handle(
                (result, throwable) -> {
                    long duration = System.currentTimeMillis() - startTime;
                    if (throwable != null) {
                        log.error(
                                "Failed to send message to Kafka: topic={}, key={}, eventId={}, duration={}ms",
                                topic,
                                key,
                                eventId,
                                duration,
                                throwable);
                        meterRegistry
                                .counter(
                                        "kafka.producer.error",
                                        "topic",
                                        topic,
                                        "error_type",
                                        throwable.getClass().getSimpleName())
                                .increment();
                        meterRegistry
                                .timer("kafka.producer.duration", "topic", topic, "status", "failed")
                                .record(duration, java.util.concurrent.TimeUnit.MILLISECONDS);
                        throw new RuntimeException("Failed to send message to Kafka", throwable);
                    }
                    RecordMetadata metadata = result.getRecordMetadata();
                    lastPartition = metadata.partition();
                    lastOffset = metadata.offset();
                    log.debug(
                            "Successfully sent message to Kafka: topic={}, partition={}, offset={}, key={},"
                            + " eventId={}, duration={}ms",
                            metadata.topic(),
                            metadata.partition(),
                            metadata.offset(),
                            key,
                            eventId,
                            duration);
                    meterRegistry
                            .counter(
                                    "kafka.producer.success",
                                    "topic",
                                    topic,
                                    "partition",
                                    String.valueOf(metadata.partition()))
                            .increment();
                    meterRegistry
                            .timer("kafka.producer.duration", "topic", topic, "status", "success")
                            .record(duration, java.util.concurrent.TimeUnit.MILLISECONDS);
                    return new PublishResult(metadata.partition(), metadata.offset());
                });
    }

    @Getter
    @RequiredArgsConstructor
    public static class PublishResult {

        private final int partition;
        private final long offset;
    }

    /**
     * Producer record oluştur (headers ile)
     */
    private ProducerRecord<String, String> createProducerRecord(
            String topic, String key, String payload, String eventId) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        // Headers ekle
        record.headers().add(new RecordHeader("event-id", eventId.getBytes(StandardCharsets.UTF_8)));
        record
                .headers()
                .add(
                        new RecordHeader(
                                "timestamp",
                                String.valueOf(Instant.now().toEpochMilli()).getBytes(StandardCharsets.UTF_8)));
        record
                .headers()
                .add(new RecordHeader("source", "outbox-publisher".getBytes(StandardCharsets.UTF_8)));
        return record;
    }
}
