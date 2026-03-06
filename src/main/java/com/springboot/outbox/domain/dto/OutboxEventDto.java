package com.springboot.outbox.domain.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.springboot.outbox.domain.entity.OutboxEvent;
import com.springboot.outbox.domain.enums.EventStatus;
import com.springboot.outbox.domain.enums.EventType;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Outbox event response")
public class OutboxEventDto {

    @Schema(description = "Event ID")
    private UUID id;

    @Schema(description = "Aggregate ID")
    private String aggregateId;

    @Schema(description = "Event type")
    private EventType eventType;

    @Schema(description = "Event payload (JSON)")
    private String payload;

    @Schema(description = "Event status")
    private EventStatus status;

    @Schema(description = "Retry count")
    private Integer retryCount;

    @Schema(description = "Maximum retry attempts")
    private Integer maxRetry;

    @Schema(description = "Error message (if failed)")
    private String errorMessage;

    @Schema(description = "Kafka topic")
    private String kafkaTopic;

    @Schema(description = "Kafka partition")
    private Integer kafkaPartition;

    @Schema(description = "Kafka offset")
    private Long kafkaOffset;

    @Schema(description = "Creation timestamp")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @Schema(description = "Last update timestamp")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    @Schema(description = "Processing timestamp")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime processedAt;

    public static OutboxEventDto from(OutboxEvent event) {
        return OutboxEventDto.builder()
                .id(event.getId())
                .aggregateId(event.getAggregateId())
                .eventType(event.getEventType())
                .payload(event.getPayload())
                .status(event.getStatus())
                .retryCount(event.getRetryCount())
                .maxRetry(event.getMaxRetry())
                .errorMessage(event.getErrorMessage())
                .kafkaTopic(event.getKafkaTopic())
                .kafkaPartition(event.getKafkaPartition())
                .kafkaOffset(event.getKafkaOffset())
                .createdAt(event.getCreatedAt())
                .updatedAt(event.getUpdatedAt())
                .processedAt(event.getProcessedAt())
                .build();
    }
}
