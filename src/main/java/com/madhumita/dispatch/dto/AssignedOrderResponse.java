package com.madhumita.dispatch.dto;

import com.madhumita.dispatch.domain.Priority;

/**
 * One delivery stop on a vehicle's route. Stops are listed in driving order.
 * distanceFromPreviousKm is measured from the vehicle's start (stop 1) or the previous drop point (later stops).
 */
public record AssignedOrderResponse(int stopNumber, String orderId, double latitude, double longitude,
                                    String address, double packageWeight, Priority priority,
                                    double distanceFromPreviousKm, double cumulativeDistanceKm) { }
