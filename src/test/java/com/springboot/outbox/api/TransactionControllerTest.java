package com.springboot.outbox.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springboot.outbox.domain.dto.TransactionRequest;
import com.springboot.outbox.domain.dto.TransactionResponse;
import com.springboot.outbox.domain.entity.Transaction;
import com.springboot.outbox.service.TransactionService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TransactionController.class)
@DisplayName("Transaction Controller Tests")
class TransactionControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockBean private TransactionService transactionService;

  private Transaction testTransaction;
  private TransactionRequest testRequest;

  @BeforeEach
  void setUp() {
    testTransaction =
        Transaction.builder()
            .id(UUID.randomUUID())
            .transactionReference("TXN-123")
            .customerId("CUST-456")
            .amount(BigDecimal.valueOf(1000.00))
            .currency("USD")
            .senderAccount("ACC-111")
            .receiverAccount("ACC-222")
            .description("Test transaction")
            .status("PENDING")
            .createdAt(LocalDateTime.now())
            .build();

    testRequest =
        TransactionRequest.builder()
            .customerId("CUST-456")
            .amount(BigDecimal.valueOf(1000.00))
            .currency("USD")
            .senderAccount("ACC-111")
            .receiverAccount("ACC-222")
            .description("Test transaction")
            .build();
  }

  @Test
  @DisplayName("POST /api/v1/transactions - Should create transaction successfully")
  void shouldCreateTransaction() throws Exception {
    when(transactionService.createTransaction(any(TransactionRequest.class)))
        .thenReturn(TransactionResponse.from(testTransaction));

    mockMvc
        .perform(
            post("/api/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testRequest)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.transactionReference").value("TXN-123"))
        .andExpect(jsonPath("$.customerId").value("CUST-456"))
        .andExpect(jsonPath("$.amount").value(1000.00))
        .andExpect(jsonPath("$.status").value("PENDING"));

    verify(transactionService, times(1)).createTransaction(any(TransactionRequest.class));
  }

  @Test
  @DisplayName("POST /api/v1/transactions - Should return 400 for invalid request")
  void shouldReturn400ForInvalidRequest() throws Exception {
    TransactionRequest invalidRequest =
        TransactionRequest.builder().amount(BigDecimal.valueOf(-100)).build();

    mockMvc
        .perform(
            post("/api/v1/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest)))
        .andExpect(status().isBadRequest());

    verify(transactionService, never()).createTransaction(any());
  }

  @Test
  @DisplayName("PUT /api/v1/transactions/{id}/status - Should update transaction status")
  void shouldUpdateTransactionStatus() throws Exception {
    UUID transactionId = testTransaction.getId();
    testTransaction.setStatus("COMPLETED");
    when(transactionService.updateTransactionStatus(eq(transactionId), eq("COMPLETED")))
        .thenReturn(TransactionResponse.from(testTransaction));

    mockMvc
        .perform(
            put("/api/v1/transactions/{transactionId}/status", transactionId)
                .param("status", "COMPLETED")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));

    verify(transactionService, times(1)).updateTransactionStatus(transactionId, "COMPLETED");
  }
}
