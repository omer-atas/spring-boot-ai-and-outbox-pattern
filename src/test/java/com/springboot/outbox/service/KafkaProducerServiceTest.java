package com.springboot.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.springboot.outbox.domain.entity.OutboxEvent;
import com.springboot.outbox.domain.enums.EventStatus;
import com.springboot.outbox.domain.enums.EventType;
import com.springboot.outbox.kafka.KafkaProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("Kafka Producer Tests")
class KafkaProducerTest {

  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  private KafkaProducer kafkaProducer;

  private OutboxEvent testEvent;

  @BeforeEach
  void setUp() {
    kafkaProducer = new KafkaProducer(kafkaTemplate, new SimpleMeterRegistry());

    testEvent =
        OutboxEvent.builder()
            .id(UUID.randomUUID())
            .aggregateId("TXN-123")
            .eventType(EventType.TRANSACTION_CREATED)
            .payload("{\"amount\":100.00}")
            .status(EventStatus.PENDING)
            .kafkaTopic("springboot.transactions")
            .kafkaKey("TXN-123")
            .createdAt(LocalDateTime.now())
            .build();
  }

  @Test
  @DisplayName("Should send event to Kafka successfully")
  void shouldSendEventToKafka() {
    CompletableFuture<KafkaProducer.PublishResult> future =
        CompletableFuture.completedFuture(new KafkaProducer.PublishResult(0, 100L));

    when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
        .thenReturn(future.thenApply(r -> null));

    kafkaProducer.sendEvent(
        testEvent.getKafkaTopic(),
        testEvent.getKafkaKey(),
        testEvent.getPayload(),
        testEvent.getId().toString());

    verify(kafkaTemplate, times(1))
        .send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
  }

  @Test
  @DisplayName("Should handle Kafka send failure")
  void shouldHandleKafkaSendFailure() {
    CompletableFuture<Void> future =
        CompletableFuture.failedFuture(new RuntimeException("Kafka error"));

    when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
        .thenReturn(future);

    try {
      kafkaProducer.sendEvent(
          testEvent.getKafkaTopic(),
          testEvent.getKafkaKey(),
          testEvent.getPayload(),
          testEvent.getId().toString());
    } catch (Exception e) {
      assertThat(e.getMessage()).contains("Failed to send");
    }
  }
}
