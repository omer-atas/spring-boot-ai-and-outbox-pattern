package com.springboot.outbox.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.springboot.outbox.domain.entity.OutboxEvent;
import com.springboot.outbox.domain.enums.EventStatus;
import com.springboot.outbox.domain.enums.EventType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("Outbox Event Repository Tests")
class OutboxEventRepositoryTest {

  @Autowired private TestEntityManager entityManager;

  @Autowired private OutboxEventRepository outboxEventRepository;

  private OutboxEvent testEvent;

  @BeforeEach
  void setUp() {
    outboxEventRepository.deleteAll();
    testEvent =
        OutboxEvent.builder()
            .aggregateId("TXN-123")
            .eventType(EventType.TRANSACTION_CREATED)
            .payload("{\"amount\":100.00}")
            .status(EventStatus.PENDING)
            .retryCount(0)
            .maxRetry(3)
            .kafkaTopic("springboot.transactions")
            .build();
  }

  @Test
  @DisplayName("Should save and find outbox event")
  void shouldSaveAndFindOutboxEvent() {
    OutboxEvent saved = outboxEventRepository.save(testEvent);
    entityManager.flush();
    entityManager.clear();

    OutboxEvent found = outboxEventRepository.findById(saved.getId()).orElseThrow();
    assertThat(found.getAggregateId()).isEqualTo(testEvent.getAggregateId());
    assertThat(found.getEventType()).isEqualTo(testEvent.getEventType());
    assertThat(found.getStatus()).isEqualTo(EventStatus.PENDING);
  }

  @Test
  @DisplayName("Should find pending events with limit")
  void shouldFindPendingEventsWithLimit() {
    for (int i = 0; i < 5; i++) {
      OutboxEvent event =
          OutboxEvent.builder()
              .aggregateId("TXN-" + i)
              .eventType(EventType.TRANSACTION_CREATED)
              .payload("{}")
              .status(EventStatus.PENDING)
              .build();
      outboxEventRepository.save(event);
    }
    entityManager.flush();

    LocalDateTime beforeTime = LocalDateTime.now().plusSeconds(5);
    List<OutboxEvent> events =
        outboxEventRepository.findPendingEventsForProcessing(
            EventStatus.PENDING, beforeTime, org.springframework.data.domain.PageRequest.of(0, 3));

    assertThat(events).hasSize(3);
    assertThat(events).allMatch(e -> e.getStatus() == EventStatus.PENDING);
  }

  @Test
  @DisplayName("Should count by status")
  void shouldCountByStatus() {
    outboxEventRepository.save(testEvent);
    OutboxEvent failed =
        OutboxEvent.builder()
            .aggregateId("TXN-002")
            .eventType(EventType.TRANSACTION_CREATED)
            .payload("{}")
            .status(EventStatus.FAILED)
            .build();
    outboxEventRepository.save(failed);
    entityManager.flush();

    long pendingCount = outboxEventRepository.countByStatus(EventStatus.PENDING);
    long failedCount = outboxEventRepository.countByStatus(EventStatus.FAILED);

    assertThat(pendingCount).isEqualTo(1);
    assertThat(failedCount).isEqualTo(1);
  }

  @Test
  @DisplayName("Should find failed events for retry")
  void shouldFindFailedEventsForRetry() {
    OutboxEvent failedRetryable =
        OutboxEvent.builder()
            .aggregateId("TXN-001")
            .eventType(EventType.TRANSACTION_CREATED)
            .payload("{}")
            .status(EventStatus.FAILED)
            .retryCount(1)
            .maxRetry(3)
            .updatedAt(LocalDateTime.now().minusMinutes(10))
            .build();
    OutboxEvent failedMaxRetry =
        OutboxEvent.builder()
            .aggregateId("TXN-002")
            .eventType(EventType.TRANSACTION_CREATED)
            .payload("{}")
            .status(EventStatus.FAILED)
            .retryCount(3)
            .maxRetry(3)
            .build();
    outboxEventRepository.save(failedRetryable);
    outboxEventRepository.save(failedMaxRetry);
    entityManager.flush();

    LocalDateTime beforeTime = LocalDateTime.now();
    List<OutboxEvent> retryableEvents =
        outboxEventRepository.findFailedEventsForRetry(
            EventStatus.FAILED, beforeTime, org.springframework.data.domain.PageRequest.of(0, 10));

    assertThat(retryableEvents).hasSize(1);
    assertThat(retryableEvents.get(0).getRetryCount()).isLessThan(3);
  }

  @Test
  @DisplayName("Should delete old processed events")
  void shouldDeleteOldProcessedEvents() {
    OutboxEvent oldProcessed =
        OutboxEvent.builder()
            .aggregateId("TXN-OLD")
            .eventType(EventType.TRANSACTION_CREATED)
            .payload("{}")
            .status(EventStatus.PROCESSED)
            .processedAt(LocalDateTime.now().minusDays(10))
            .build();
    OutboxEvent recentProcessed =
        OutboxEvent.builder()
            .aggregateId("TXN-RECENT")
            .eventType(EventType.TRANSACTION_CREATED)
            .payload("{}")
            .status(EventStatus.PROCESSED)
            .processedAt(LocalDateTime.now().minusDays(3))
            .build();
    outboxEventRepository.save(oldProcessed);
    outboxEventRepository.save(recentProcessed);
    entityManager.flush();

    LocalDateTime cutoffDate = LocalDateTime.now().minusDays(7);
    int deleted =
        outboxEventRepository.deleteProcessedEventsBefore(EventStatus.PROCESSED, cutoffDate);

    assertThat(deleted).isEqualTo(1);
    assertThat(outboxEventRepository.findAll()).hasSize(1);
  }

  @Test
  @DisplayName("Should find by aggregate id")
  void shouldFindByAggregateId() {
    String aggregateId = "TXN-AGGREGATE";
    OutboxEvent event1 =
        OutboxEvent.builder()
            .aggregateId(aggregateId)
            .eventType(EventType.TRANSACTION_CREATED)
            .payload("{}")
            .status(EventStatus.PENDING)
            .build();
    OutboxEvent event2 =
        OutboxEvent.builder()
            .aggregateId(aggregateId)
            .eventType(EventType.TRANSACTION_UPDATED)
            .payload("{}")
            .status(EventStatus.PROCESSED)
            .build();
    outboxEventRepository.save(event1);
    outboxEventRepository.save(event2);
    entityManager.flush();

    List<OutboxEvent> events =
        outboxEventRepository.findByAggregateIdOrderByCreatedAtDesc(aggregateId);

    assertThat(events).hasSize(2);
    assertThat(events).allMatch(e -> e.getAggregateId().equals(aggregateId));
  }

  @Test
  @DisplayName("Should mark stuck events as failed")
  void shouldMarkStuckEventsAsFailed() {
    OutboxEvent stuckEvent =
        OutboxEvent.builder()
            .aggregateId("TXN-STUCK")
            .eventType(EventType.TRANSACTION_CREATED)
            .payload("{}")
            .status(EventStatus.PROCESSING)
            .updatedAt(LocalDateTime.now().minusHours(2))
            .build();
    outboxEventRepository.save(stuckEvent);
    entityManager.flush();

    LocalDateTime threshold = LocalDateTime.now().minusMinutes(30);
    int count = outboxEventRepository.markStuckEventsAsFailed(threshold);

    assertThat(count).isEqualTo(1);
  }
}
