package com.madhumita.dispatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

/** Documented shape of every error body produced by GlobalExceptionHandler. */
@Schema(description = "Standard error response")
public record ErrorResponse(
        @Schema(example = "2026-11-18T06:18:17.475Z") Instant timestamp,
        @Schema(example = "400") int status,
        @Schema(example = "Bad Request") String error,
        @Schema(example = "Validation failed") String message,
        @Schema(description = "Field path -> problem, e.g. vehicles[0].capacity -> must be greater than 0",
                example = "{\"vehicles[0].capacity\":\"must be greater than 0\"}") Map<String, String> details) { }
