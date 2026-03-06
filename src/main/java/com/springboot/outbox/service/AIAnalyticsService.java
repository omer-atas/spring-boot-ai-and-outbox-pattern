package com.springboot.outbox.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springboot.outbox.domain.entity.OutboxEvent;
import com.springboot.outbox.domain.enums.EventStatus;
import com.springboot.outbox.domain.enums.EventType;
import com.springboot.outbox.repository.OutboxEventRepository;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIAnalyticsService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Outbox pattern performans analizi - AI ile
     */
    @Timed(value = "ai.analytics.performance", description = "Time to analyze performance with AI")
    public String analyzeOutboxPerformance() {
        log.info("Starting AI-powered outbox performance analysis");
        // Outbox istatistiklerini topla
        Map<String, Object> statistics = collectOutboxStatistics();
        // Prompt oluştur
        String promptText
                = """
            Analyze the following outbox pattern metrics and provide insights:
            Statistics:
            - Total Pending Events: {pendingCount}
            - Total Processing Events: {processingCount}
            - Total Processed Events: {processedCount}
            - Total Failed Events: {failedCount}
            - Total Dead Letter Events: {deadLetterCount}
            - Average Processing Time: {avgProcessingTime} ms
            - Success Rate: {successRate}%
            - Failure Rate: {failureRate}%
            Provide:
            1. Overall health assessment
            2. Potential bottlenecks
            3. Recommendations for improvement
            4. Alert priorities (if any)
        """;
        PromptTemplate promptTemplate = new PromptTemplate(promptText);
        Prompt prompt = promptTemplate.create(statistics);
        ChatResponse response = chatClient.prompt(prompt).call().chatResponse();
        String analysis = response.getResult().getOutput().getContent();
        log.info("AI performance analysis completed");
        return analysis;
    }

    /**
     * Failed event'lerin root cause analizi - AI ile
     */
    @Timed(value = "ai.analytics.root_cause", description = "Time to analyze root cause with AI")
    public String analyzeFailureRootCause(EventType eventType) {
        log.info("Starting AI-powered root cause analysis for event type: {}", eventType);
        // Son 24 saatteki failed eventleri al
        List<OutboxEvent> failedEvents
                = outboxEventRepository
                        .findByEventType(eventType, org.springframework.data.domain.PageRequest.of(0, 100))
                        .stream()
                        .filter(
                                e
                                -> e.getStatus() == EventStatus.FAILED || e.getStatus() == EventStatus.DEAD_LETTER)
                        .filter(e -> e.getCreatedAt().isAfter(LocalDateTime.now().minusHours(24)))
                        .toList();
        if (failedEvents.isEmpty()) {
            return "No failed events found for analysis in the last 24 hours.";
        }
        // Error mesajlarını grupla
        Map<String, Long> errorGroups
                = failedEvents.stream()
                        .collect(
                                Collectors.groupingBy(
                                        e -> e.getErrorMessage() != null ? e.getErrorMessage() : "Unknown",
                                        Collectors.counting()));
        // Prompt oluştur
        String promptText
                = """
                Analyze the following failure patterns for {eventType} events:
                Total Failed Events: {totalFailed}
                Time Period: Last 24 hours
                Error Distribution:
                {errorDistribution}
                Provide:
                1. Root cause analysis
                2. Common patterns in failures
                3. Immediate action items
                4. Long-term prevention strategies
                """;
        Map<String, Object> model = new HashMap<>();
        model.put("eventType", eventType.name());
        model.put("totalFailed", failedEvents.size());
        model.put("errorDistribution", formatErrorDistribution(errorGroups));
        PromptTemplate promptTemplate = new PromptTemplate(promptText);
        Prompt prompt = promptTemplate.create(model);
        ChatResponse response = chatClient.prompt(prompt).call().chatResponse();
        String analysis = response.getResult().getOutput().getContent();
        log.info("AI root cause analysis completed for event type: {}", eventType);
        return analysis;
    }

    /**
     * Event pattern'lerini vektör store'da ara - Semantic search
     */
    @Timed(value = "ai.analytics.pattern_search", description = "Time to search patterns with AI")
    public List<String> searchSimilarEventPatterns(String query, int topK) {
        log.info("Searching for similar event patterns: query={}, topK={}", query, topK);
        // Vektör store'da semantic search yap
        SearchRequest searchRequest
                = SearchRequest.query(query).withTopK(topK).withSimilarityThreshold(0.7);
        List<Document> documents = vectorStore.similaritySearch(searchRequest);
        return documents.stream().map(Document::getContent).collect(Collectors.toList());
    }

    /**
     * Event'leri vektör store'a indexle
     */
    @Timed(value = "ai.analytics.index_events", description = "Time to index events")
    public void indexEventsToVectorStore() {
        log.info("Starting to index outbox events to vector store");
        // Son 7 gündeki tüm eventleri al
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        List<OutboxEvent> events
                = outboxEventRepository.findAll().stream()
                        .filter(e -> e.getCreatedAt().isAfter(since))
                        .toList();
        List<Document> documents = events.stream().map(this::convertEventToDocument).toList();
        vectorStore.add(documents);
        log.info("Indexed {} events to vector store", documents.size());
    }

    /**
     * Anomaly detection - AI ile
     */
    @Timed(value = "ai.analytics.anomaly_detection", description = "Time to detect anomalies")
    public String detectAnomalies() {
        log.info("Starting AI-powered anomaly detection");
        // Son 1 saatlik event istatistikleri
        Map<EventType, Long> hourlyStats = collectHourlyEventStats();
        // Son 24 saatlik ortalama
        Map<EventType, Double> dailyAverage = collectDailyAverageStats();
        String promptText
                = """
                Analyze the following event processing patterns and detect anomalies:
                Last Hour Statistics:
                {hourlyStats}
                Daily Average (Last 24 hours):
                {dailyAverage}
                Detect:
                1. Unusual spikes or drops in event volumes
                2. Performance degradation patterns
                3. Potential system issues
                4. Recommended immediate actions
                """;
        Map<String, Object> model = new HashMap<>();
        model.put("hourlyStats", formatStats(hourlyStats));
        model.put("dailyAverage", formatStats(dailyAverage));
        PromptTemplate promptTemplate = new PromptTemplate(promptText);
        Prompt prompt = promptTemplate.create(model);
        ChatResponse response = chatClient.prompt(prompt).call().chatResponse();
        String analysis = response.getResult().getOutput().getContent();
        log.info("AI anomaly detection completed");
        return analysis;
    }

    /**
     * Retry stratejisi önerisi - AI ile
     */
    @Timed(value = "ai.analytics.retry_strategy", description = "Time to suggest retry strategy")
    public String suggestRetryStrategy(UUID eventId) {
        OutboxEvent event
                = outboxEventRepository
                        .findById(eventId)
                        .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
        String promptText
                = """
        Analyze the following failed event and suggest an optimal retry strategy:
        Event Details:
        - Event Type: {eventType}
        - Retry Count: {retryCount}
        - Max Retry: {maxRetry}
        - Error Message: {errorMessage}
        - Failed At: {failedAt}
        - Time Since Creation: {timeSinceCreation} minutes
        Suggest:
        1. Should this event be retried?
        2. Optimal retry delay
        3. Any prerequisites before retry
        4. Alternative handling strategies
        """;
        Map<String, Object> model = new HashMap<>();
        model.put("eventType", event.getEventType().name());
        model.put("retryCount", event.getRetryCount());
        model.put("maxRetry", event.getMaxRetry());
        model.put("errorMessage", event.getErrorMessage());
        model.put("failedAt", event.getUpdatedAt());
        model.put(
                "timeSinceCreation",
                java.time.Duration.between(event.getCreatedAt(), LocalDateTime.now()).toMinutes());
        PromptTemplate promptTemplate = new PromptTemplate(promptText);
        Prompt prompt = promptTemplate.create(model);
        ChatResponse response = chatClient.prompt(prompt).call().chatResponse();
        return response.getResult().getOutput().getContent();
    }

    /**
     * Capacity planning - AI ile
     */
    @Timed(value = "ai.analytics.capacity_planning", description = "Time for capacity planning")
    public String analyzeCapacityNeeds() {
        log.info("Starting AI-powered capacity planning analysis");
        Map<String, Object> metrics = collectCapacityMetrics();
        String promptText
                = """
                Analyze the following outbox system metrics and provide capacity planning recommendations:
                Current Metrics:
                - Average Events per Hour: {avgEventsPerHour}
                - Peak Events per Hour: {peakEventsPerHour}
                - Average Processing Time: {avgProcessingTime} ms
                - Current Batch Size: {batchSize}
                - Scheduler Frequency: {schedulerFrequency} seconds
                - Database Connection Pool Size: {connectionPoolSize}
                Projected Growth: {projectedGrowth}%
                Provide:
                1. Current system capacity assessment
                2. Bottleneck identification
                3. Scaling recommendations (vertical vs horizontal)
                4. Configuration optimizations
                5. Infrastructure requirements for projected growth
                """;
        PromptTemplate promptTemplate = new PromptTemplate(promptText);
        Prompt prompt = promptTemplate.create(metrics);
        ChatResponse response = chatClient.prompt(prompt).call().chatResponse();
        String analysis = response.getResult().getOutput().getContent();
        log.info("AI capacity planning analysis completed");
        return analysis;
    }

    // Helper methods
    private Map<String, Object> collectOutboxStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("pendingCount", outboxEventRepository.countByStatus(EventStatus.PENDING));
        stats.put("processingCount", outboxEventRepository.countByStatus(EventStatus.PROCESSING));
        stats.put("processedCount", outboxEventRepository.countByStatus(EventStatus.PROCESSED));
        stats.put("failedCount", outboxEventRepository.countByStatus(EventStatus.FAILED));
        stats.put("deadLetterCount", outboxEventRepository.countByStatus(EventStatus.DEAD_LETTER));
        // Success rate hesapla
        long total = outboxEventRepository.count();
        long processed = (long) stats.get("processedCount");
        long failed = (long) stats.get("failedCount") + (long) stats.get("deadLetterCount");
        stats.put("successRate", total > 0 ? (processed * 100.0 / total) : 0);
        stats.put("failureRate", total > 0 ? (failed * 100.0 / total) : 0);
        stats.put("avgProcessingTime", calculateAverageProcessingTime());
        return stats;
    }

    private Map<EventType, Long> collectHourlyEventStats() {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getCreatedAt().isAfter(oneHourAgo))
                .collect(Collectors.groupingBy(OutboxEvent::getEventType, Collectors.counting()));
    }

    private Map<EventType, Double> collectDailyAverageStats() {
        LocalDateTime oneDayAgo = LocalDateTime.now().minusDays(1);
        Map<EventType, Long> dailyCounts
                = outboxEventRepository.findAll().stream()
                        .filter(e -> e.getCreatedAt().isAfter(oneDayAgo))
                        .collect(Collectors.groupingBy(OutboxEvent::getEventType, Collectors.counting()));
        // 24 saatlik ortalamaya böl
        return dailyCounts.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue() / 24.0));
    }

    private Map<String, Object> collectCapacityMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        // Son 24 saatlik event sayısı
        LocalDateTime oneDayAgo = LocalDateTime.now().minusDays(1);
        long totalEventsLastDay
                = outboxEventRepository.findAll().stream()
                        .filter(e -> e.getCreatedAt().isAfter(oneDayAgo))
                        .count();
        metrics.put("avgEventsPerHour", totalEventsLastDay / 24.0);
        metrics.put("peakEventsPerHour", calculatePeakEventsPerHour());
        metrics.put("avgProcessingTime", calculateAverageProcessingTime());
        metrics.put("batchSize", 100); // Config'den al
        metrics.put("schedulerFrequency", 5); // Config'den al
        metrics.put("connectionPoolSize", 10); // Config'den al
        metrics.put("projectedGrowth", 20); // Business tarafından belirlenir
        return metrics;
    }

    private double calculateAverageProcessingTime() {
        LocalDateTime oneDayAgo = LocalDateTime.now().minusDays(1);
        List<OutboxEvent> processedEvents
                = outboxEventRepository.findAll().stream()
                        .filter(e -> e.getStatus() == EventStatus.PROCESSED)
                        .filter(e -> e.getProcessedAt() != null)
                        .filter(e -> e.getCreatedAt().isAfter(oneDayAgo))
                        .toList();
        if (processedEvents.isEmpty()) {
            return 0.0;
        }
        double totalTime
                = processedEvents.stream()
                        .mapToDouble(
                                e -> java.time.Duration.between(e.getCreatedAt(), e.getProcessedAt()).toMillis())
                        .sum();
        return totalTime / processedEvents.size();
    }

    private long calculatePeakEventsPerHour() {
        LocalDateTime oneDayAgo = LocalDateTime.now().minusDays(1);
        Map<Integer, Long> hourlyDistribution
                = outboxEventRepository.findAll().stream()
                        .filter(e -> e.getCreatedAt().isAfter(oneDayAgo))
                        .collect(Collectors.groupingBy(e -> e.getCreatedAt().getHour(), Collectors.counting()));
        return hourlyDistribution.values().stream().max(Long::compareTo).orElse(0L);
    }

    private Document convertEventToDocument(OutboxEvent event) {
        String content
                = String.format(
                        "Event Type: %s, Status: %s, Aggregate: %s, Error: %s, Created: %s",
                        event.getEventType(),
                        event.getStatus(),
                        event.getAggregateId(),
                        event.getErrorMessage() != null ? event.getErrorMessage() : "None",
                        event.getCreatedAt());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("eventId", event.getId().toString());
        metadata.put("eventType", event.getEventType().name());
        metadata.put("status", event.getStatus().name());
        metadata.put("aggregateId", event.getAggregateId());
        return new Document(event.getId().toString(), content, metadata);
    }

    private String formatErrorDistribution(Map<String, Long> errorGroups) {
        return errorGroups.entrySet().stream()
                .map(e -> String.format("- %s: %d occurrences", e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));
    }

    private String formatStats(Map<?, ?> stats) {
        return stats.entrySet().stream()
                .map(e -> String.format("- %s: %s", e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));
    }
}
