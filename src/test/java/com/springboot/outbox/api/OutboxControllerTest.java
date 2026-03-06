package com.springboot.outbox.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.springboot.outbox.domain.entity.OutboxEvent;
import com.springboot.outbox.domain.enums.EventStatus;
import com.springboot.outbox.domain.enums.EventType;
import com.springboot.outbox.repository.OutboxEventRepository;
import com.springboot.outbox.service.OutboxPublisher;
import com.springboot.outbox.service.OutboxService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OutboxController.class)
@DisplayName("Outbox Controller Tests")
class OutboxControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private OutboxEventRepository outboxEventRepository;

  @MockBean private OutboxService outboxService;

  @MockBean private OutboxPublisher outboxPublisher;

  private OutboxEvent testEvent;

  @BeforeEach
  void setUp() {
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
  @DisplayName("GET /api/v1/outbox/events - Should return paginated events")
  void shouldGetAllEvents() throws Exception {
    when(outboxEventRepository.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(Arrays.asList(testEvent)));

    mockMvc
        .perform(get("/api/v1/outbox/events").param("page", "0").param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content[0].aggregateId").value("TXN-123"));

    verify(outboxEventRepository, times(1)).findAll(any(Pageable.class));
  }

  @Test
  @DisplayName("GET /api/v1/outbox/events/{eventId} - Should return event by id")
  void shouldGetEventById() throws Exception {
    UUID eventId = testEvent.getId();
    when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(testEvent));

    mockMvc
        .perform(get("/api/v1/outbox/events/{eventId}", eventId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.aggregateId").value("TXN-123"));

    verify(outboxEventRepository, times(1)).findById(eventId);
  }

  @Test
  @DisplayName("GET /api/v1/outbox/events/status/{status} - Should return event count by status")
  void shouldGetEventsByStatus() throws Exception {
    when(outboxEventRepository.countByStatus(EventStatus.PENDING)).thenReturn(10L);

    mockMvc
        .perform(get("/api/v1/outbox/events/status/{status}", "PENDING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").value(10));

    verify(outboxEventRepository, times(1)).countByStatus(EventStatus.PENDING);
  }

  @Test
  @DisplayName("GET /api/v1/outbox/statistics - Should return outbox statistics")
  void shouldGetOutboxStats() throws Exception {
    when(outboxEventRepository.countByStatus(EventStatus.PENDING)).thenReturn(10L);
    when(outboxEventRepository.countByStatus(EventStatus.PROCESSING)).thenReturn(5L);
    when(outboxEventRepository.countByStatus(EventStatus.PROCESSED)).thenReturn(100L);
    when(outboxEventRepository.countByStatus(EventStatus.FAILED)).thenReturn(2L);
    when(outboxEventRepository.countByStatus(EventStatus.DEAD_LETTER)).thenReturn(1L);
    when(outboxEventRepository.count()).thenReturn(118L);

    mockMvc
        .perform(get("/api/v1/outbox/statistics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.PENDING").value(10))
        .andExpect(jsonPath("$.PROCESSING").value(5))
        .andExpect(jsonPath("$.PROCESSED").value(100))
        .andExpect(jsonPath("$.FAILED").value(2))
        .andExpect(jsonPath("$.DEAD_LETTER").value(1))
        .andExpect(jsonPath("$.TOTAL").value(118));
  }

  @Test
  @DisplayName("POST /api/v1/outbox/events/{eventId}/retry - Should retry event")
  void shouldRetryEvent() throws Exception {
    UUID eventId = testEvent.getId();
    when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(testEvent));
    when(outboxEventRepository.save(any(OutboxEvent.class))).thenReturn(testEvent);

    mockMvc
        .perform(post("/api/v1/outbox/events/{eventId}/retry", eventId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").value("Event queued for retry"));

    verify(outboxEventRepository, times(1)).findById(eventId);
    verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
  }

  @Test
  @DisplayName("POST /api/v1/outbox/trigger-processing - Should trigger manual processing")
  void shouldTriggerManualProcessing() throws Exception {
    doNothing().when(outboxPublisher).processPendingEvents();

    mockMvc
        .perform(post("/api/v1/outbox/trigger-processing"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").value("Processing triggered"));

    verify(outboxPublisher, times(1)).processPendingEvents();
  }

  @Test
  @DisplayName("GET /api/v1/outbox/dead-letter - Should return dead letter events")
  void shouldGetDeadLetterEvents() throws Exception {
    testEvent.setStatus(EventStatus.DEAD_LETTER);
    when(outboxEventRepository.findDeadLetterEvents(any(Pageable.class)))
        .thenReturn(Arrays.asList(testEvent));

    mockMvc
        .perform(get("/api/v1/outbox/dead-letter").param("limit", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());

    verify(outboxEventRepository, times(1)).findDeadLetterEvents(any(Pageable.class));
  }
}
