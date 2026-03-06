package com.springboot.outbox.integration;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.springboot.outbox.domain.dto.TransactionRequest;
import com.springboot.outbox.domain.dto.TransactionResponse;
import com.springboot.outbox.domain.entity.OutboxEvent;
import com.springboot.outbox.domain.enums.EventStatus;
import com.springboot.outbox.domain.enums.EventType;
import com.springboot.outbox.repository.OutboxEventRepository;
import com.springboot.outbox.service.OutboxPublisher;
import com.springboot.outbox.service.TransactionService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
    partitions = 1,
    topics = {"springboot.transactions", "springboot.payments"},
    brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"})
@DirtiesContext
@DisplayName("Outbox Pattern Integration Tests")
class OutboxIntegrationTest {

  @Autowired private TransactionService transactionService;

  @Autowired private OutboxEventRepository outboxEventRepository;

  @Autowired private OutboxPublisher outboxPublisher;

  @BeforeEach
  void setUp() {
    outboxEventRepository.deleteAll();
  }

  @Test
  @DisplayName("Should create transaction and outbox event atomically")
  void shouldCreateTransactionAndOutboxEventAtomically() {
    TransactionRequest request =
        TransactionRequest.builder()
            .customerId("CUST-123")
            .amount(BigDecimal.valueOf(1000.00))
            .currency("USD")
            .senderAccount("ACC-111")
            .receiverAccount("ACC-222")
            .description("Test transaction")
            .build();

    TransactionResponse response = transactionService.createTransaction(request);

    assertThat(response.getId()).isNotNull();
    assertThat(response.getStatus()).isEqualTo("PENDING");

    List<OutboxEvent> outboxEvents =
        outboxEventRepository.findByAggregateIdOrderByCreatedAtDesc(response.getId().toString());
    assertThat(outboxEvents).hasSize(1);
    OutboxEvent event = outboxEvents.get(0);
    assertThat(event.getEventType()).isEqualTo(EventType.TRANSACTION_CREATED);
    assertThat(event.getStatus()).isEqualTo(EventStatus.PENDING);
  }

  @Test
  @DisplayName("Should process pending outbox events")
  void shouldProcessPendingOutboxEvents() {
    TransactionRequest request =
        TransactionRequest.builder()
            .customerId("CUST-456")
            .amount(BigDecimal.valueOf(500.00))
            .currency("EUR")
            .senderAccount("ACC-333")
            .receiverAccount("ACC-444")
            .build();

    TransactionResponse response = transactionService.createTransaction(request);

    await()
        .atMost(15, SECONDS)
        .pollInterval(1, SECONDS)
        .untilAsserted(
            () -> {
              List<OutboxEvent> events =
                  outboxEventRepository.findByAggregateIdOrderByCreatedAtDesc(
                      response.getId().toString());
              assertThat(events).isNotEmpty();
              assertThat(events.get(0).getStatus()).isEqualTo(EventStatus.PROCESSED);
            });

    List<OutboxEvent> processedEvents =
        outboxEventRepository.findByAggregateIdOrderByCreatedAtDesc(response.getId().toString());
    assertThat(processedEvents).hasSize(1);
    OutboxEvent event = processedEvents.get(0);
    assertThat(event.getProcessedAt()).isNotNull();
    assertThat(event.getKafkaPartition()).isNotNull();
    assertThat(event.getKafkaOffset()).isNotNull();
  }

  @Test
  @DisplayName("Should mark event as processing and failed on Kafka error")
  void shouldHandleKafkaPublishFailure() {
    OutboxEvent failedEvent =
        OutboxEvent.builder()
            .id(UUID.randomUUID())
            .aggregateId("TXN-MANUAL-001")
            .eventType(EventType.TRANSACTION_CREATED)
            .payload("{\"error\":\"test\"}")
            .status(EventStatus.PENDING)
            .retryCount(0)
            .maxRetry(3)
            .kafkaTopic("springboot.transactions")
            .kafkaKey("TXN-MANUAL-001")
            .build();
    outboxEventRepository.save(failedEvent);

    outboxPublisher.processPendingEvents();

    await()
        .atMost(5, SECONDS)
        .pollInterval(500, SECONDS)
        .untilAsserted(
            () -> {
              OutboxEvent event = outboxEventRepository.findById(failedEvent.getId()).orElseThrow();
              assertThat(event.getStatus()).isIn(EventStatus.PROCESSING, EventStatus.FAILED);
            });
  }

  @Test
  @DisplayName("Should mark event as dead letter after max retries")
  void shouldMoveToDeadLetterAfterMaxRetries() {
    OutboxEvent maxRetriedEvent =
        OutboxEvent.builder()
            .id(UUID.randomUUID())
            .aggregateId("TXN-MAX-001")
            .eventType(EventType.TRANSACTION_CREATED)
            .payload("{\"status\":\"dead\"}")
            .status(EventStatus.FAILED)
            .retryCount(3)
            .maxRetry(3)
            .errorMessage("Max retries reached")
            .kafkaTopic("springboot.transactions")
            .kafkaKey("TXN-MAX-001")
            .build();
    outboxEventRepository.save(maxRetriedEvent);

    await()
        .atMost(5, SECONDS)
        .pollInterval(500, SECONDS)
        .untilAsserted(
            () -> {
              OutboxEvent event =
                  outboxEventRepository.findById(maxRetriedEvent.getId()).orElseThrow();
              assertThat(event.getStatus()).isEqualTo(EventStatus.DEAD_LETTER);
            });
  }
}
