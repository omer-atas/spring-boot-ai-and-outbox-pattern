package com.springboot.outbox.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.springboot.outbox.domain.dto.TransactionRequest;
import com.springboot.outbox.domain.dto.TransactionResponse;
import com.springboot.outbox.domain.entity.Transaction;
import com.springboot.outbox.domain.enums.EventType;
import com.springboot.outbox.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

  @Mock private TransactionRepository transactionRepository;

  @Mock private OutboxService outboxService;

  private TransactionService transactionService;

  private TransactionRequest request;
  private Transaction transaction;

  @BeforeEach
  void setUp() {
    transactionService =
        new TransactionService(transactionRepository, outboxService, new SimpleMeterRegistry());
    ReflectionTestUtils.setField(transactionService, "transactionTopic", "springboot.transactions");

    request =
        TransactionRequest.builder()
            .customerId("CUST-1")
            .amount(new BigDecimal("100.00"))
            .currency("EUR")
            .senderAccount("ACC-1")
            .receiverAccount("ACC-2")
            .description("Test")
            .build();

    transaction =
        Transaction.builder()
            .id(UUID.randomUUID())
            .transactionReference("TRX-1")
            .customerId("CUST-1")
            .amount(new BigDecimal("100.00"))
            .status("PENDING")
            .createdAt(java.time.LocalDateTime.now())
            .build();
  }

  @Test
  void createTransaction_ShouldSaveAndCreateOutboxEvent() {
    when(transactionRepository.save(any(Transaction.class))).thenReturn(transaction);

    TransactionResponse response = transactionService.createTransaction(request);

    assertNotNull(response);
    verify(transactionRepository, times(1)).save(any(Transaction.class));
    verify(outboxService, times(1))
        .createOutboxEvent(
            eq(transaction.getId().toString()),
            eq(EventType.TRANSACTION_CREATED),
            any(),
            eq("springboot.transactions"));
  }
}
