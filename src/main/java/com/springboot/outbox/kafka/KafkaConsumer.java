package com.springboot.outbox.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaConsumer {

    private final MeterRegistry meterRegistry;

    /**
     * Transaction events consumer
     */
    @KafkaListener(
            topics = "${kafka.topics.transactions}",
            groupId = "${kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void consumeTransactionEvent(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            log.info(
                    "Received message: topic={}, partition={}, offset={}, key={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key());
            // Event'i işle
            processEvent(record);
            // Manual commit
            acknowledgment.acknowledge();
            meterRegistry
                    .counter(
                            "kafka.consumer.success",
                            "topic",
                            record.topic(),
                            "partition",
                            String.valueOf(record.partition()))
                    .increment();
        } catch (Exception e) {
            log.error(
                    "Error processing message: topic={}, partition={}, offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    e);
            meterRegistry
                    .counter(
                            "kafka.consumer.error",
                            "topic",
                            record.topic(),
                            "error_type",
                            e.getClass().getSimpleName())
                    .increment();
            // Error handling - DLQ'ya gönder veya retry
            // acknowledgment.nack(Duration.ofSeconds(10)); // Reprocess after delay
        }
    }

    private void processEvent(ConsumerRecord<String, String> record) {
        // Event processing logic
        String payload = record.value();
        log.info("Processing event payload: {}", payload);
        // Business logic burada
    }
}
