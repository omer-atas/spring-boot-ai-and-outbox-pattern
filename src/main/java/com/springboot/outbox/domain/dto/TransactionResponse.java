package com.springboot.outbox.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.springboot.outbox.domain.entity.Transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Transaction response")
public class TransactionResponse {

    @Schema(description = "Transaction ID")
    private UUID id;

    @Schema(description = "Transaction reference number")
    private String transactionReference;

    @Schema(description = "Customer ID")
    private String customerId;

    @Schema(description = "Transaction amount")
    private BigDecimal amount;

    @Schema(description = "Currency code")
    private String currency;

    @Schema(description = "Sender account")
    private String senderAccount;

    @Schema(description = "Receiver account")
    private String receiverAccount;

    @Schema(description = "Transaction description")
    private String description;

    @Schema(description = "Transaction status")
    private String status;

    @Schema(description = "Creation timestamp")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @Schema(description = "Last update timestamp")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    public static TransactionResponse from(Transaction transaction) {
        return TransactionResponse.builder()
                .id(transaction.getId())
                .transactionReference(transaction.getTransactionReference())
                .customerId(transaction.getCustomerId())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .senderAccount(transaction.getSenderAccount())
                .receiverAccount(transaction.getReceiverAccount())
                .description(transaction.getDescription())
                .status(transaction.getStatus())
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .build();
    }
}
