package com.madhumita.dispatch.dto;

import java.util.List;

public record VehiclePlanResponse(
        String vehicleId,
        double capacity,
        double currentLatitude,
        double currentLongitude,
        String currentAddress,
        double totalLoad,
        double remainingCapacity,
        double totalDistanceKm,
        List<AssignedOrderResponse> assignedOrders) { }
