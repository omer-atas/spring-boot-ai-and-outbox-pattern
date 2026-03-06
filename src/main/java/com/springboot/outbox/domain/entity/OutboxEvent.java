package com.springboot.outbox.domain.entity;

import com.springboot.outbox.domain.enums.EventStatus;
import com.springboot.outbox.domain.enums.EventType;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "outbox_events",
    indexes = {
      @Index(name = "idx_status_created", columnList = "status,created_at"),
      @Index(name = "idx_aggregate_id", columnList = "aggregate_id"),
      @Index(name = "idx_event_type", columnList = "event_type")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "aggregate_id", nullable = false, length = 100)
  private String aggregateId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 50)
  private EventType eventType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  @Builder.Default
  private EventStatus status = EventStatus.PENDING;

  @Column(name = "retry_count", nullable = false)
  @Builder.Default
  private Integer retryCount = 0;

  @Column(name = "max_retry", nullable = false)
  @Builder.Default
  private Integer maxRetry = 3;

  @Column(name = "created_at", nullable = false, updatable = false)
  @Builder.Default
  private LocalDateTime createdAt = LocalDateTime.now();

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @Column(name = "processed_at")
  private LocalDateTime processedAt;

  @Column(name = "error_message", columnDefinition = "text")
  private String errorMessage;

  @Column(name = "kafka_topic", length = 100)
  private String kafkaTopic;

  @Column(name = "kafka_key", length = 100)
  private String kafkaKey;

  @Column(name = "kafka_partition")
  private Integer kafkaPartition;

  @Column(name = "kafka_offset")
  private Long kafkaOffset;

  @Version
  @Column(name = "version")
  private Long version;

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = LocalDateTime.now();
  }

  public void markAsProcessed() {
    this.status = EventStatus.PROCESSED;
    this.processedAt = LocalDateTime.now();
    this.updatedAt = LocalDateTime.now();
  }

  public void markAsFailed(String errorMessage) {
    this.status = EventStatus.FAILED;
    this.errorMessage = errorMessage;
    this.retryCount++;
    this.updatedAt = LocalDateTime.now();
  }

  public void markAsDeadLetter() {
    this.status = EventStatus.DEAD_LETTER;
    this.updatedAt = LocalDateTime.now();
  }

  public boolean canRetry() {
    return this.retryCount < this.maxRetry;
  }
}
