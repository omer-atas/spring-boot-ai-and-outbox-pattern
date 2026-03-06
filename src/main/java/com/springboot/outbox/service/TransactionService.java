package com.springboot.outbox.service;

import com.springboot.outbox.domain.dto.TransactionRequest;
import com.springboot.outbox.domain.dto.TransactionResponse;
import com.springboot.outbox.domain.entity.Transaction;
import com.springboot.outbox.domain.enums.EventType;
import com.springboot.outbox.exception.OutboxException;
import com.springboot.outbox.repository.TransactionRepository;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final OutboxService outboxService;
    private final MeterRegistry meterRegistry;

    @Value("${kafka.topics.transactions}")
    private String transactionTopic;

    /**
     * Transaction oluştur - Outbox pattern ile Aynı DB transaction içinde hem
     * transaction hem outbox eventi kaydet
     */
    @Transactional
    @Timed(value = "transaction.create", description = "Time to create transaction")
    public TransactionResponse createTransaction(TransactionRequest request) {
        log.info(
                "Creating transaction: customerid={}, amount={} {}",
                request.getCustomerId(),
                request.getAmount(),
                request.getCurrency());
        try {
            // 1. Transaction entity oluştur
            Transaction transaction
                    = Transaction.builder()
                            .transactionReference(generateTransactionReference())
                            .customerId(request.getCustomerId())
                            .amount(request.getAmount())
                            .currency(request.getCurrency())
                            .senderAccount(request.getSenderAccount())
                            .receiverAccount(request.getReceiverAccount())
                            .description(request.getDescription())
                            .status("PENDING")
                            .createdAt(LocalDateTime.now())
                            .build();
            // 2. Transaction'ı kaydet
            Transaction savedTransaction = transactionRepository.save(transaction);
            // 3. Outbox eventi oluştur (aynı transaction içinde!)
            outboxService.createOutboxEvent(
                    savedTransaction.getId().toString(),
                    EventType.TRANSACTION_CREATED,
                    savedTransaction,
                    transactionTopic);
            log.info(
                    "Transaction created successfully: id={}, reference={}",
                    savedTransaction.getId(),
                    savedTransaction.getTransactionReference());
            meterRegistry
                    .counter("transaction.created", "currency", request.getCurrency(), "status", "success")
                    .increment();
            return TransactionResponse.from(savedTransaction);
        } catch (Exception e) {
            log.error("Failed to create transaction: customerId={}", request.getCustomerId(), e);
            meterRegistry
                    .counter("transaction.created", "currency", request.getCurrency(), "status", "failed")
                    .increment();
            throw new OutboxException("Failed to create transaction", e);
        }
    }

    /**
     * Transaction'ı güncelle
     */
    @Transactional
    @Timed(value = "transaction.update", description = "Time to update transaction")
    public TransactionResponse updateTransactionStatus(UUID transactionId, String status) {
        log.info("Updating transaction status: id={}, status={}", transactionId, status);
        Transaction transaction
                = transactionRepository
                        .findById(transactionId)
                        .orElseThrow(() -> new OutboxException("Transaction not found: " + transactionId));
        String oldStatus = transaction.getStatus();
        transaction.setStatus(status);
        transaction.setUpdatedAt(LocalDateTime.now());
        Transaction updatedTransaction = transactionRepository.save(transaction);
        // Status değişikliği için event oluştur
        EventType eventType = determineEventType(status);
        outboxService.createOutboxEvent(
                updatedTransaction.getId().toString(), eventType, updatedTransaction, transactionTopic);
        log.info(
                "Transaction status updated: id={}, oldStatus={}, newStatus={}",
                transactionId,
                oldStatus,
                status);
        meterRegistry
                .counter("transaction.status.updated", "old_status", oldStatus, "new_status", status)
                .increment();
        return TransactionResponse.from(updatedTransaction);
    }

    /**
     * Transaction reference oluştur
     */
    private String generateTransactionReference() {
        return "TRX-"
                + System.currentTimeMillis()
                + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * Status'e göre event type belirle
     */
    private EventType determineEventType(String status) {
        return switch (status.toUpperCase()) {
            case "APPROVED" ->
                EventType.TRANSACTION_APPROVED;
            case "REJECTED" ->
                EventType.TRANSACTION_REJECTED;
            case "COMPLETED" ->
                EventType.PAYMENT_COMPLETED;
            case "FAILED" ->
                EventType.PAYMENT_FAILED;
            default ->
                EventType.TRANSACTION_UPDATED;
        };
    }
}
