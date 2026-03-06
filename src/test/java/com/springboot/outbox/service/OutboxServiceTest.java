package com.springboot.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springboot.outbox.domain.entity.OutboxEvent;
import com.springboot.outbox.domain.enums.EventStatus;
import com.springboot.outbox.domain.enums.EventType;
import com.springboot.outbox.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Outbox Service Tests")
class OutboxServiceTest {

  @Mock private OutboxEventRepository outboxEventRepository;

  private OutboxService outboxService;

  private OutboxEvent testEvent;

  @BeforeEach
  void setUp() {
    outboxService =
        new OutboxService(outboxEventRepository, new ObjectMapper(), new SimpleMeterRegistry());

    testEvent =
        OutboxEvent.builder()
            .id(UUID.randomUUID())
            .aggregateId("TXN-123")
            .eventType(EventType.TRANSACTION_CREATED)
            .payload("{\"amount\":100.00}")
            .status(EventStatus.PENDING)
            .retryCount(0)
            .maxRetry(3)
            .createdAt(LocalDateTime.now())
            .build();
  }

  @Test
  @DisplayName("Should create outbox event successfully")
  void shouldCreateOutboxEvent() {
    when(outboxEventRepository.save(any(OutboxEvent.class))).thenReturn(testEvent);

    OutboxEvent saved =
        outboxService.createOutboxEvent(
            "TXN-123", EventType.TRANSACTION_CREATED, testEvent, "springboot.transactions");

    assertThat(saved).isNotNull();
    assertThat(saved.getStatus()).isEqualTo(EventStatus.PENDING);
    verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
  }

  @Test
  @DisplayName("Should mark event as processing")
  void shouldMarkEventAsProcessing() {
    UUID eventId = testEvent.getId();
    when(outboxEventRepository.findById(eventId)).thenReturn(java.util.Optional.of(testEvent));
    when(outboxEventRepository.save(any(OutboxEvent.class))).thenReturn(testEvent);

    outboxService.markAsProcessing(eventId);

    verify(outboxEventRepository, times(1)).findById(eventId);
    verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
  }

  @Test
  @DisplayName("Should mark event as processed")
  void shouldMarkEventAsProcessed() {
    UUID eventId = testEvent.getId();
    when(outboxEventRepository.findById(eventId)).thenReturn(java.util.Optional.of(testEvent));
    when(outboxEventRepository.save(any(OutboxEvent.class))).thenReturn(testEvent);

    outboxService.markAsProcessed(eventId, 0, 100L);

    verify(outboxEventRepository, times(1)).findById(eventId);
    verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
  }

  @Test
  @DisplayName("Should mark event as failed")
  void shouldMarkEventAsFailed() {
    UUID eventId = testEvent.getId();
    when(outboxEventRepository.findById(eventId)).thenReturn(java.util.Optional.of(testEvent));
    when(outboxEventRepository.save(any(OutboxEvent.class))).thenReturn(testEvent);

    outboxService.markAsFailed(eventId, "Test error");

    verify(outboxEventRepository, times(1)).findById(eventId);
    verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
  }

  @Test
  @DisplayName("Should move to dead letter when max retry reached")
  void shouldMoveToDeadLetterWhenMaxRetryReached() {
    testEvent.setStatus(EventStatus.FAILED);
    testEvent.setRetryCount(3);
    testEvent.setMaxRetry(3);
    UUID eventId = testEvent.getId();

    when(outboxEventRepository.findById(eventId)).thenReturn(java.util.Optional.of(testEvent));
    when(outboxEventRepository.save(any(OutboxEvent.class))).thenReturn(testEvent);

    outboxService.markAsFailed(eventId, "Max retries exceeded");

    verify(outboxEventRepository, times(1)).findById(eventId);
    verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
  }

  @Test
  @DisplayName("Should handle stuck events")
  void shouldHandleStuckEvents() {
    when(outboxEventRepository.markStuckEventsAsFailed(any(LocalDateTime.class))).thenReturn(5);

    int count = outboxService.handleStuckEvents(30);

    assertThat(count).isEqualTo(5);
    verify(outboxEventRepository, times(1)).markStuckEventsAsFailed(any(LocalDateTime.class));
  }
}
