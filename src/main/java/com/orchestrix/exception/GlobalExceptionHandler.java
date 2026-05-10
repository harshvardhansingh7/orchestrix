package com.orchestrix.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrchestrixException.class)
    public ResponseEntity<ErrorBody> handleOrchestrix(OrchestrixException ex) {
        log.warn("orchestrix-error type={} status={} msg={}", ex.getClass().getSimpleName(),
                ex.httpStatus(), ex.getMessage());
        return ResponseEntity.status(ex.httpStatus())
                .body(error(ex.getClass().getSimpleName(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.badRequest().body(error("ValidationException", detail));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpected(Exception ex) {
        log.error("unhandled error", ex);
        return ResponseEntity.status(500).body(error("InternalError", "unexpected error"));
    }

    private ErrorBody error(String type, String message) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestId", MDC.get("requestId"));
        meta.put("tenantId", MDC.get("tenantId"));
        return new ErrorBody(type, message, Instant.now().toString(), meta);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String type, String message, String timestamp, Map<String, Object> meta) {}
}
