package com.madhumita.dispatch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.madhumita.dispatch.domain.DeliveryOrder;
import com.madhumita.dispatch.domain.Priority;
import com.madhumita.dispatch.domain.Vehicle;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DispatchOptimizerTest {
    private final DispatchOptimizer optimizer = new DispatchOptimizer(new DistanceCalculator());

    /** The 30-order / 5-vehicle assignment dataset. Optimum proven independently with a CP-SAT model. */
    @Test void sampleDatasetReachesProvenOptimumPriorityCountsAndRouteDistance() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<DeliveryOrder> orders = new ArrayList<>();
        for (JsonNode n : mapper.readTree(new File("sample-data/orders.json")).get("orders")) {
            orders.add(new DeliveryOrder(n.get("orderId").asText(), n.get("latitude").asDouble(),
                    n.get("longitude").asDouble(), n.get("address").asText(),
                    n.get("packageWeight").asDouble(), Priority.valueOf(n.get("priority").asText())));
        }
        List<Vehicle> vehicles = new ArrayList<>();
        for (JsonNode n : mapper.readTree(new File("sample-data/vehicles.json"))) {
            vehicles.add(new Vehicle(n.get("vehicleId").asText(), n.get("capacity").asDouble(),
                    n.get("currentLatitude").asDouble(), n.get("currentLongitude").asDouble(),
                    n.get("currentAddress").asText()));
        }

        var result = optimizer.optimize(orders, vehicles);

        assertEquals(List.of(12, 8, 4), result.assignedCountsByPriority());
        assertEquals(34.237, result.totalDistanceKm(), 0.01);

        Map<String, DeliveryOrder> byId = new HashMap<>();
        orders.forEach(o -> byId.put(o.getOrderId(), o));
        Set<String> seen = new HashSet<>();
        for (Vehicle v : vehicles) {
            var route = result.routeByVehicleId().get(v.getVehicleId());
            if (route == null) continue;
            double load = 0;
            for (var leg : route.legs()) {
                assertTrue(seen.add(leg.orderId()), "order assigned twice: " + leg.orderId());
                load += byId.get(leg.orderId()).getPackageWeight();
            }
            assertTrue(load <= v.getCapacity() + 1e-9, v.getVehicleId() + " is over capacity");
        }
    }

    /** Delhi-area orders with two far-away vehicles (Bangalore, Chennai). Under the LOW-priority detour limit (50 km/order) the proven CP-SAT optimum is 1779.261 km:
     *  only ONE far vehicle is sent to Delhi (needed for HIGH/MEDIUM); the other stays home. */
    @Test void farAwayVehiclesAreOnlyUsedWhenPriorityRequiresItAndRoutesAreOptimal() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<DeliveryOrder> orders = new ArrayList<>();
        for (JsonNode n : mapper.readTree(new File("sample-data/orders.json")).get("orders")) {
            orders.add(new DeliveryOrder(n.get("orderId").asText(), n.get("latitude").asDouble(),
                    n.get("longitude").asDouble(), n.get("address").asText(),
                    n.get("packageWeight").asDouble(), Priority.valueOf(n.get("priority").asText())));
        }
        List<Vehicle> vehicles = new ArrayList<>();
        for (JsonNode n : mapper.readTree(new File("sample-data/vehicles-bangalore-chennai.json"))) {
            vehicles.add(new Vehicle(n.get("vehicleId").asText(), n.get("capacity").asDouble(),
                    n.get("currentLatitude").asDouble(), n.get("currentLongitude").asDouble(),
                    n.get("currentAddress").asText()));
        }
        var result = optimizer.optimize(orders, vehicles);
        assertEquals(12, result.assignedCountsByPriority().get(0));
        assertEquals(8, result.assignedCountsByPriority().get(1));
        assertEquals(1779.261, result.totalDistanceKm(), 0.01);
        long farVehiclesUsed = result.routeByVehicleId().keySet().stream()
                .filter(id -> id.equals("VEH001") || id.equals("VEH002")).count();
        assertEquals(1, farVehiclesUsed, "only one of the Bangalore/Chennai vehicles should be dispatched");
    }

    @Test void routeVisitsDropsInTheShortestOrderNotInputOrder() {
        // Vehicle at the west end of a line; drops given far-first. Best order is near -> middle -> far.
        List<DeliveryOrder> orders = List.of(
                new DeliveryOrder("FAR", 0, 0.3, "far", 1, Priority.HIGH),
                new DeliveryOrder("NEAR", 0, 0.1, "near", 1, Priority.HIGH),
                new DeliveryOrder("MID", 0, 0.2, "mid", 1, Priority.HIGH));
        var route = optimizer.optimize(orders, List.of(new Vehicle("V", 10, 0, 0, "start"))).routeByVehicleId().get("V");
        assertEquals(List.of("NEAR", "MID", "FAR"), route.legs().stream().map(l -> l.orderId()).toList());
        assertEquals(33.36, route.totalDistanceKm(), 0.02);
    }

    @Test void highPriorityOrdersAreAssignedBeforeBetterFittingLowerPriorityOnes() {
        List<DeliveryOrder> orders = List.of(
                new DeliveryOrder("H", 0, 0, "h", 7, Priority.HIGH),
                new DeliveryOrder("L1", 0, 0, "l1", 5, Priority.LOW),
                new DeliveryOrder("L2", 0, 0, "l2", 5, Priority.LOW));
        var result = optimizer.optimize(orders, List.of(new Vehicle("V", 10, 0, 0, "start")));
        assertEquals(List.of(1, 0, 0), result.assignedCountsByPriority());
    }

    @Test void emptyInputsProduceEmptyResult() {
        assertTrue(optimizer.optimize(List.of(), List.of(new Vehicle("V", 10, 0, 0, "s"))).routeByVehicleId().isEmpty());
        assertTrue(optimizer.optimize(List.of(new DeliveryOrder("O", 0, 0, "a", 1, Priority.LOW)), List.of())
                .routeByVehicleId().isEmpty());
    }

    @Test void lowPriorityOrderIsNotWorthALongDetour() {
        // Near vehicle is full after the HIGH order; the only way to deliver the LOW order is a ~1100 km trip.
        List<DeliveryOrder> orders = List.of(
                new DeliveryOrder("H", 0, 0, "h", 10, Priority.HIGH),
                new DeliveryOrder("L", 0, 0, "l", 10, Priority.LOW));
        List<Vehicle> vehicles = List.of(new Vehicle("NEAR", 10, 0, 0, "near"), new Vehicle("FAR", 10, 10, 0, "far"));
        var result = optimizer.optimize(orders, vehicles);
        assertEquals(List.of(1, 0, 0), result.assignedCountsByPriority());
        assertEquals(0.0, result.totalDistanceKm(), 1e-9);
        assertFalse(result.routeByVehicleId().containsKey("FAR"));
    }

    @Test void lowPriorityOrderIsDeliveredWhenItCostsNothingExtra() {
        List<DeliveryOrder> orders = List.of(
                new DeliveryOrder("H", 0, 0.1, "h", 4, Priority.HIGH),
                new DeliveryOrder("L", 0, 0.1, "l", 4, Priority.LOW));
        var result = optimizer.optimize(orders, List.of(new Vehicle("V", 10, 0, 0, "s")));
        assertEquals(List.of(1, 0, 1), result.assignedCountsByPriority());
    }
}
