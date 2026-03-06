package com.springboot.outbox.domain.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Transaction creation request")
public class TransactionRequest {

    @NotBlank(message = "Customer ID is required")
    @Size(max = 50, message = "Customer ID must not exceed 50 characters")
    @Schema(description = "Customer identifier", example = "CUST-12345")
    private String customerId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    @Digits(integer = 15, fraction = 4, message = "Invalid amount format")
    @Schema(description = "Transaction amount", example = "1000.50")
    private BigDecimal amount;

    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter ISO code")
    @Schema(description = "Currency code (ISO 4217)", example = "EUR")
    private String currency;

    @NotBlank(message = "Sender account is required")
    @Size(max = 50, message = "Sender account must not exceed 50 characters")
    @Schema(description = "Sender account number", example = "NL91ABNA0417164300")
    private String senderAccount;

    @NotBlank(message = "Receiver account is required")
    @Size(max = 50, message = "Receiver account must not exceed 50 characters")
    @Schema(description = "Receiver account number", example = "NL20INGB0001234567")
    private String receiverAccount;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    @Schema(description = "Transaction description", example = "Payment for invoice #12345")
    private String description;
}
