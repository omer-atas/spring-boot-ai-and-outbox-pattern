package com.springboot.outbox.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.springboot.outbox.domain.entity.OutboxEvent;
import com.springboot.outbox.domain.enums.EventStatus;
import com.springboot.outbox.domain.enums.EventType;

import jakarta.persistence.LockModeType;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Pessimistic locking ile PENDING durumundaki eventleri getir Concurrent
     * processing için kritik
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
      SELECT e FROM OutboxEvent e
      WHERE e.status = :status
      AND e.createdAt <= :beforeTime
      ORDER BY e.createdAt ASC
      """)
    List<OutboxEvent> findPendingEventsForProcessing(
            @Param("status") EventStatus status,
            @Param("beforeTime") LocalDateTime beforeTime,
            Pageable pageable);

    /**
     * Retry edilmesi gereken FAILED eventleri getir
     */
    @Query("""
                  SELECT e FROM OutboxEvent e
                  WHERE e.status = :status
                  AND e.retryCount < e.maxRetry
                  AND e.updatedAt <= :beforeTime
                  ORDER BY e.retryCount ASC, e.createdAt ASC
      """)
    List<OutboxEvent> findFailedEventsForRetry(
            @Param("status") EventStatus status,
            @Param("beforeTime") LocalDateTime beforeTime,
            Pageable pageable);

    /**
     * Aggregate ID'ye göre tüm eventleri getir
     */
    List<OutboxEvent> findByAggregateIdOrderByCreatedAtDesc(String aggregateId);

    /**
     * Event type'a göre eventleri getir
     */
    Page<OutboxEvent> findByEventType(EventType eventType, Pageable pageable);

    /**
     * Belirli bir tarihten önce PROCESSED durumundaki eventleri sil (cleanup)
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.processedAt < :beforeDate")
    int deleteProcessedEventsBefore(
            @Param("status") EventStatus status, @Param("beforeDate") LocalDateTime beforeDate);

    /**
     * Status'e göre count
     */
    long countByStatus(EventStatus status);

    /**
     * Event type ve status'e göre count
     */
    long countByEventTypeAndStatus(EventType eventType, EventStatus status);

    /**
     * Belirli bir aggregate için en son eventi getir
     */
    Optional<OutboxEvent> findFirstByAggregateIdOrderByCreatedAtDesc(String aggregateId);

    /**
     * DEAD_LETTER durumundaki eventleri getir
     */
    @Query("""
      SELECT e FROM OutboxEvent e
      WHERE e.status = 'DEAD_LETTER'
      ORDER BY e.updatedAt DESC
      """)
    List<OutboxEvent> findDeadLetterEvents(Pageable pageable);

    /**
     * Belirli bir süre PROCESSING durumunda kalan eventleri FAILED yap (stuck
     * event detection)
     */
    @Modifying
    @Query("""
      UPDATE OutboxEvent e
      SET e.status = 'FAILED',
      e.errorMessage = 'Processing timeout - event stuck',
      e.updatedAt = CURRENT_TIMESTAMP
      WHERE e.status = 'PROCESSING'
      AND e.updatedAt < :beforeTime
      """)
    int markStuckEventsAsFailed(@Param("beforeTime") LocalDateTime beforeTime);
}
