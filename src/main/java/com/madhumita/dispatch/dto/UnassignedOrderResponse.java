package com.madhumita.dispatch.dto;
import com.madhumita.dispatch.domain.Priority;
public record UnassignedOrderResponse(String orderId, double packageWeight, Priority priority, String reason) { }
