package com.madhumita.dispatch.dto;

import com.madhumita.dispatch.domain.Priority;
import jakarta.validation.constraints.*;

/** Validated API representation of one delivery order. Wrapper types let us reject omitted numbers. */
public record OrderInput(
        @NotBlank @Size(max = 100) String orderId,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @NotBlank @Size(max = 500) String address,
        @NotNull @Positive Double packageWeight,
        @NotNull Priority priority
) { }
