package com.springboot.outbox.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.springboot.outbox.domain.entity.AuditLog;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByEntityTypeAndEntityIdOrderByTimestampDesc(
            String entityType, String entityId);

    List<AuditLog> findByUserIdOrderByTimestampDesc(String userId);
}
