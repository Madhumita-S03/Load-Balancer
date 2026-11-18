package com.madhumita.dispatch.dto;

import jakarta.validation.constraints.*;

/** Validated API representation of a fleet vehicle. */
public record VehicleInput(
        @NotBlank @Size(max = 100) String vehicleId,
        @NotNull @Positive Double capacity,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double currentLatitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double currentLongitude,
        @NotBlank @Size(max = 500) String currentAddress
) { }
