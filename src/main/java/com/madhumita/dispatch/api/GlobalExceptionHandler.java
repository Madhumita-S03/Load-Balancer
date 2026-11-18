package com.madhumita.dispatch.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String,Object>> handleInvalidRequest(InvalidRequestException ex) {
        return response(HttpStatus.BAD_REQUEST, ex.getMessage(), ex.getDetails());
    }
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String,Object>> handleMethodValidation(HandlerMethodValidationException ex) {
        Map<String,String> errors = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(r -> r.getResolvableErrors()
                .forEach(e -> errors.putIfAbsent("request", e.getDefaultMessage())));
        return response(HttpStatus.BAD_REQUEST, "Validation failed", errors);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> handleUnexpected(Exception ex) {
        // Spring's own web exceptions (404, 405, 415, ...) already know their status; keep it.
        if (ex instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatus status = HttpStatus.resolve(springError.getStatusCode().value());
            if (status != null) return response(status, String.valueOf(springError.getBody().getDetail()), Map.of());
        }
        log.error("Unexpected error", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error. Please retry or contact the administrator.", Map.of());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String,Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String,String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        return response(HttpStatus.BAD_REQUEST, "Validation failed", errors);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return response(HttpStatus.BAD_REQUEST, ex.getMessage(), Map.of());
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        String message = "Invalid JSON request. Check the request body structure, field names, data types, and supported values.";
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof InvalidFormatException invalidFormat) {
                Class<?> targetType = invalidFormat.getTargetType();
                if (targetType != null && targetType.isEnum()) {
                    String field = invalidFormat.getPath().isEmpty()
                            ? "value"
                            : invalidFormat.getPath().get(invalidFormat.getPath().size() - 1).getFieldName();
                    String allowed = java.util.Arrays.stream(targetType.getEnumConstants())
                            .map(Object::toString).collect(java.util.stream.Collectors.joining(", "));
                    if ("priority".equals(field) && "HIGH, MEDIUM, LOW".equals(allowed)) {
                        message = "Invalid priority. Priority must be HIGH, MEDIUM, or LOW.";
                    } else {
                        message = "Invalid value for '" + field + "'. Allowed values: " + allowed + ".";
                    }
                }
                break;
            }
            cause = cause.getCause();
        }
        return response(HttpStatus.BAD_REQUEST, message, Map.of());
    }
    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Map<String,Object>> handlePersistenceConflict(Exception ex) {
        return response(HttpStatus.CONFLICT,
                "A record with the same business ID was changed concurrently or violates a uniqueness constraint. Retry the request.",
                Map.of());
    }

    private ResponseEntity<Map<String,Object>> response(HttpStatus status, String message, Map<String,?> details) {
        Map<String,Object> body = new LinkedHashMap<>(); body.put("timestamp", Instant.now()); body.put("status", status.value()); body.put("error", status.getReasonPhrase()); body.put("message", message); body.put("details", details);
        return ResponseEntity.status(status).body(body);
    }
}
