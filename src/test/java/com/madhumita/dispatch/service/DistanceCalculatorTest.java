package com.madhumita.dispatch.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DistanceCalculatorTest {
    private final DistanceCalculator calculator = new DistanceCalculator();

    @Test void samePointHasZeroDistance() {
        assertEquals(0.0, calculator.haversineKm(12.0, 77.0, 12.0, 77.0), 1e-9);
    }
    @Test void calculatesKnownApproximateDistance() {
        // Bengaluru MG Road to Chennai Anna Salai is approximately 290 km straight-line.
        double distance = calculator.haversineKm(12.9716, 77.5946, 13.0827, 80.2707);
        assertTrue(distance > 285 && distance < 300, "Unexpected distance: " + distance);
    }
    @Test void handlesAntipodalCoordinatesWithoutNan() {
        double distance = calculator.haversineKm(0.0, 0.0, 0.0, 180.0);
        assertTrue(Double.isFinite(distance));
        assertEquals(Math.PI * 6371.0088, distance, 1e-6);
    }
    @Test void isSymmetric() {
        double forward = calculator.haversineKm(28.6139, 77.2090, 28.7041, 77.1025);
        double reverse = calculator.haversineKm(28.7041, 77.1025, 28.6139, 77.2090);
        assertEquals(forward, reverse, 1e-9);
    }
}
