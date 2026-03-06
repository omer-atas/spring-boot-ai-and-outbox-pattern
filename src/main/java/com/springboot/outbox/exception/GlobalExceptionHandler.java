package com.springboot.outbox.exception;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.WebRequest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MeterRegistry meterRegistry;

    @ExceptionHandler(OutboxException.class)
    public ResponseEntity<ErrorResponse> handleOutboxException(
            OutboxException ex, WebRequest request) {
        log.error("Outbox exception occurred", ex);
        incrementErrorCounter("OutboxException");
        ErrorResponse error
                = ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .error("Outbox Error")
                        .message(ex.getMessage())
                        .path(request.getDescription(false).replace("uri=", ""))
                        .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {
        log.error("Illegal argument exception occurred", ex);
        incrementErrorCounter("IllegalArgumentException");
        ErrorResponse error
                = ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.BAD_REQUEST.value())
                        .error("Bad Request")
                        .message(ex.getMessage())
                        .path(request.getDescription(false).replace("uri=", ""))
                        .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(
            MethodArgumentNotValidException ex, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult()
                .getAllErrors()
                .forEach(
                        error -> {
                            String fieldName = ((FieldError) error).getField();
                            String errorMessage = error.getDefaultMessage();
                            errors.put(fieldName, errorMessage);
                        });
        log.error("Validation exception occurred: {}", errors);
        incrementErrorCounter("ValidationException");
        ErrorResponse error
                = ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.BAD_REQUEST.value())
                        .error("Validation Failed")
                        .message("Invalid request parameters")
                        .path(request.getDescription(false).replace("uri=", ""))
                        .validationErrors(errors)
                        .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleResourceAccessException(
            ResourceAccessException ex, WebRequest request) {
        log.error("AI service (Ollama) connection failed: {}", ex.getMessage());
        incrementErrorCounter("ResourceAccessException");
        ErrorResponse error
                = ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                        .error("AI Service Unavailable")
                        .message("AI analytics service (Ollama) is not reachable. Ensure Ollama is running.")
                        .path(request.getDescription(false).replace("uri=", ""))
                        .build();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(Exception ex, WebRequest request) {
        log.error("Unexpected exception occurred", ex);
        incrementErrorCounter("UnexpectedException");
        ErrorResponse error
                = ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .error("Internal Server Error")
                        .message("An unexpected error occurred")
                        .path(request.getDescription(false).replace("uri=", ""))
                        .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private void incrementErrorCounter(String exceptionType) {
        Counter.builder("api.errors")
                .tag("exception_type", exceptionType)
                .register(meterRegistry)
                .increment();
    }
}
