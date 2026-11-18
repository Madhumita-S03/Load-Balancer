package com.madhumita.dispatch.service;

import org.springframework.stereotype.Component;

/** Calculates straight-line great-circle distance between geographic coordinates. */
@Component
public class DistanceCalculator {
    // Mean radius of Earth in kilometres, used to convert the central angle to distance.
    private static final double EARTH_RADIUS_KM = 6371.0088;

    /**
     * Returns the Haversine distance in kilometres between two latitude/longitude points.
     * Input coordinates are expressed in decimal degrees; the trigonometric formula uses radians.
     */
    public double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaPhi = Math.toRadians(lat2 - lat1);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        // Haversine 'a' is based on the latitude and longitude differences.
        double sinHalfLatitude = Math.sin(deltaPhi / 2.0);
        double sinHalfLongitude = Math.sin(deltaLambda / 2.0);
        double a = sinHalfLatitude * sinHalfLatitude
                + Math.cos(phi1) * Math.cos(phi2)
                * sinHalfLongitude * sinHalfLongitude;

        // Floating-point rounding can move a just outside [0, 1] near antipodal points.
        double boundedA = Math.min(1.0, Math.max(0.0, a));
        double centralAngleRadians = 2.0 * Math.atan2(
                Math.sqrt(boundedA), Math.sqrt(1.0 - boundedA));

        return EARTH_RADIUS_KM * centralAngleRadians;
    }
}
