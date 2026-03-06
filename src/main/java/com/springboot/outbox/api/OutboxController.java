package com.springboot.outbox.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.springboot.outbox.domain.dto.OutboxEventDto;
import com.springboot.outbox.domain.entity.OutboxEvent;
import com.springboot.outbox.domain.enums.EventStatus;
import com.springboot.outbox.domain.enums.EventType;
import com.springboot.outbox.repository.OutboxEventRepository;
import com.springboot.outbox.service.OutboxPublisher;
import com.springboot.outbox.service.OutboxService;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/outbox")
@RequiredArgsConstructor
@Tag(name = "Outbox API", description = "Outbox event management and monitoring")
public class OutboxController {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxService outboxService;
    private final OutboxPublisher outboxPublisher;

    @GetMapping("/events")
    @Operation(summary = "Get all outbox events", description = "Retrieves paginated outbox events")
    @Timed(value = "api.outbox.get_events", description = "Time to get events via API")
    public ResponseEntity<Page<OutboxEventDto>> getAllEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir) {
        Sort sort
                = sortDir.equalsIgnoreCase("ASC")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        Page<OutboxEvent> events = outboxEventRepository.findAll(pageable);
        Page<OutboxEventDto> dtos = events.map(OutboxEventDto::from);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/events/{eventId}")
    @Operation(summary = "Get event by ID", description = "Retrieves a specific outbox event")
    @Timed(value = "api.outbox.get_event", description = "Time to get event by ID via API")
    public ResponseEntity<OutboxEventDto> getEventById(@PathVariable UUID eventId) {
        OutboxEvent event
                = outboxEventRepository
                        .findById(eventId)
                        .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
        return ResponseEntity.ok(OutboxEventDto.from(event));
    }

    @GetMapping("/events/aggregate/{aggregateId}")
    @Operation(
            summary = "Get events by aggregate ID",
            description = "Retrieves all events for an aggregate")
    @Timed(
            value = "api.outbox.get_events_by_aggregate",
            description = "Time to get events by aggregate via API")
    public ResponseEntity<List<OutboxEventDto>> getEventsByAggregateId(
            @PathVariable String aggregateId) {
        List<OutboxEvent> events
                = outboxEventRepository.findByAggregateIdOrderByCreatedAtDesc(aggregateId);
        List<OutboxEventDto> dtos
                = events.stream().map(OutboxEventDto::from).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/events/status/{status}")
    @Operation(summary = "Get events by status", description = "Retrieves events filtered by status")
    @Timed(
            value = "api.outbox.get_events_by_status",
            description = "Time to get events by status via API")
    public ResponseEntity<Long> getEventCountByStatus(@PathVariable EventStatus status) {
        long count = outboxEventRepository.countByStatus(status);
        return ResponseEntity.ok(count);
    }

    @GetMapping("/events/type/{eventType}")
    @Operation(
            summary = "Get events by type",
            description = "Retrieves events filtered by event type")
    @Timed(
            value = "api.outbox.get_events_by_type",
            description = "Time to get events by type via API")
    public ResponseEntity<Page<OutboxEventDto>> getEventsByType(
            @PathVariable EventType eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<OutboxEvent> events = outboxEventRepository.findByEventType(eventType, pageable);
        Page<OutboxEventDto> dtos = events.map(OutboxEventDto::from);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/statistics")
    @Operation(summary = "Get outbox statistics", description = "Retrieves outbox event statistics")
    @Timed(value = "api.outbox.get_statistics", description = "Time to get statistics via API")
    public ResponseEntity<Map<String, Long>> getStatistics() {
        Map<String, Long> stats
                = Map.of(
                        "PENDING", outboxEventRepository.countByStatus(EventStatus.PENDING),
                        "PROCESSING", outboxEventRepository.countByStatus(EventStatus.PROCESSING),
                        "PROCESSED", outboxEventRepository.countByStatus(EventStatus.PROCESSED),
                        "FAILED", outboxEventRepository.countByStatus(EventStatus.FAILED),
                        "DEAD_LETTER", outboxEventRepository.countByStatus(EventStatus.DEAD_LETTER),
                        "TOTAL", outboxEventRepository.count());
        return ResponseEntity.ok(stats);
    }

    @PostMapping("/events/{eventId}/retry")
    @Operation(
            summary = "Manually retry an event",
            description = "Manually triggers retry for a failed event")
    @Timed(value = "api.outbox.retry_event", description = "Time to retry event via API")
    public ResponseEntity<String> retryEvent(@PathVariable UUID eventId) {
        log.info("Manual retry requested for event: {}", eventId);
        OutboxEvent event
                = outboxEventRepository
                        .findById(eventId)
                        .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
        if (event.getStatus() != EventStatus.FAILED && event.getStatus() != EventStatus.DEAD_LETTER) {
            return ResponseEntity.badRequest().body("Event is not in FAILED or DEAD_LETTER status");
        }
        // Reset status to PENDING for retry
        event.setStatus(EventStatus.PENDING);
        event.setRetryCount(0);
        event.setErrorMessage(null);
        outboxEventRepository.save(event);
        return ResponseEntity.ok("Event queued for retry");
    }

    @PostMapping("/trigger-processing")
    @Operation(
            summary = "Trigger manual processing",
            description = "Manually triggers outbox event processing")
    @Timed(
            value = "api.outbox.trigger_processing",
            description = "Time to trigger processing via API")
    public ResponseEntity<String> triggerProcessing() {
        log.info("Manual processing triggered via API");
        outboxPublisher.processPendingEvents();
        return ResponseEntity.ok("Processing triggered");
    }

    @GetMapping("/dead-letter")
    @Operation(summary = "Get dead letter events", description = "Retrieves all dead letter events")
    @Timed(
            value = "api.outbox.get_dead_letter",
            description = "Time to get dead letter events via API")
    public ResponseEntity<List<OutboxEventDto>> getDeadLetterEvents(
            @RequestParam(defaultValue = "100") int limit) {
        List<OutboxEvent> events = outboxEventRepository.findDeadLetterEvents(PageRequest.of(0, limit));
        List<OutboxEventDto> dtos
                = events.stream().map(OutboxEventDto::from).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }
}
