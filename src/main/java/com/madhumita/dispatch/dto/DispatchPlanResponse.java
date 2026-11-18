package com.madhumita.dispatch.dto;
import java.util.List;
public record DispatchPlanResponse(List<VehiclePlanResponse> dispatchPlan, List<UnassignedOrderResponse> unassignedOrders, double totalAssignedDistanceKm, double totalAssignedLoad) { }
