package com.springboot.outbox.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.springboot.outbox.domain.enums.EventType;
import com.springboot.outbox.service.AIAnalyticsService;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics API", description = "AI-powered analytics and insights")
public class AnalyticsController {

    private final AIAnalyticsService aiAnalyticsService;

    @GetMapping("/performance")
    @Operation(
            summary = "Get performance analysis",
            description = "AI-powered outbox performance analysis")
    @Timed(value = "api.analytics.performance", description = "Time to analyze performance via API")
    public ResponseEntity<String> analyzePerformance() {
        log.info("Performance analysis requested via API");
        String analysis = aiAnalyticsService.analyzeOutboxPerformance();
        return ResponseEntity.ok(analysis);
    }

    @GetMapping("/root-cause/{eventType}")
    @Operation(
            summary = "Get root cause analysis",
            description = "AI-powered root cause analysis for failures")
    @Timed(value = "api.analytics.root_cause", description = "Time to analyze root cause via API")
    public ResponseEntity<String> analyzeRootCause(@PathVariable EventType eventType) {
        log.info("Root cause analysis requested for event type: {}", eventType);
        String analysis = aiAnalyticsService.analyzeFailureRootCause(eventType);
        return ResponseEntity.ok(analysis);
    }

    @GetMapping("/anomalies")
    @Operation(summary = "Detect anomalies", description = "AI-powered anomaly detection")
    @Timed(value = "api.analytics.anomalies", description = "Time to detect anomalies via API")
    public ResponseEntity<String> detectAnomalies() {
        log.info("Anomaly detection requested via API");
        String analysis = aiAnalyticsService.detectAnomalies();
        return ResponseEntity.ok(analysis);
    }

    @GetMapping("/retry-strategy/{eventId}")
    @Operation(summary = "Get retry strategy", description = "AI-powered retry strategy suggestion")
    @Timed(
            value = "api.analytics.retry_strategy",
            description = "Time to suggest retry strategy via API")
    public ResponseEntity<String> suggestRetryStrategy(@PathVariable UUID eventId) {
        log.info("Retry strategy suggestion requested for event: {}", eventId);
        String suggestion = aiAnalyticsService.suggestRetryStrategy(eventId);
        return ResponseEntity.ok(suggestion);
    }

    @GetMapping("/capacity-planning")
    @Operation(
            summary = "Get capacity planning",
            description = "AI-powered capacity planning analysis")
    @Timed(
            value = "api.analytics.capacity_planning",
            description = "Time for capacity planning via API")
    public ResponseEntity<String> analyzeCapacity() {
        log.info("Capacity planning analysis requested via API");
        String analysis = aiAnalyticsService.analyzeCapacityNeeds();
        return ResponseEntity.ok(analysis);
    }

    @GetMapping("/search-patterns")
    @Operation(
            summary = "Search similar patterns",
            description = "Search for similar event patterns using AI")
    @Timed(value = "api.analytics.search_patterns", description = "Time to search patterns via API")
    public ResponseEntity<List<String>> searchPatterns(
            @RequestParam String query, @RequestParam(defaultValue = "10") int topK) {
        log.info("Pattern search requested: query={}, topK={}", query, topK);
        List<String> patterns = aiAnalyticsService.searchSimilarEventPatterns(query, topK);
        return ResponseEntity.ok(patterns);
    }

    @PostMapping("/index-events")
    @Operation(
            summary = "Index events to vector store",
            description = "Index events for AI-powered search")
    @Timed(value = "api.analytics.index_events", description = "Time to index events via API")
    public ResponseEntity<String> indexEvents() {
        log.info("Event indexing requested via API");
        aiAnalyticsService.indexEventsToVectorStore();
        return ResponseEntity.ok("Events indexed successfully");
    }
}
