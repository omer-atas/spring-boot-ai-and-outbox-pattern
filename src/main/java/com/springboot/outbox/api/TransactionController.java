package com.springboot.outbox.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.springboot.outbox.domain.dto.TransactionRequest;
import com.springboot.outbox.domain.dto.TransactionResponse;
import com.springboot.outbox.service.TransactionService;

import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transaction API", description = "Transaction management with Outbox Pattern")
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping
    @Operation(
            summary = "Create a new transaction",
            description = "Creates a transaction with outbox event")
    @Timed(value = "api.transaction.create", description = "Time to create transaction via API")
    public ResponseEntity<TransactionResponse> createTransaction(
            @Valid @RequestBody TransactionRequest request) {
        log.info("Creating transaction via API: customerId={}", request.getCustomerId());
        TransactionResponse response = transactionService.createTransaction(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{transactionId}/status")
    @Operation(
            summary = "Update transaction status",
            description = "Updates transaction status and creates outbox event")
    @Timed(
            value = "api.transaction.update_status",
            description = "Time to update transaction status via API")
    public ResponseEntity<TransactionResponse> updateTransactionStatus(
            @PathVariable UUID transactionId, @RequestParam String status) {
        log.info("Updating transaction status via API: id={}, status={}", transactionId, status);
        TransactionResponse response
                = transactionService.updateTransactionStatus(transactionId, status);
        return ResponseEntity.ok(response);
    }
}
