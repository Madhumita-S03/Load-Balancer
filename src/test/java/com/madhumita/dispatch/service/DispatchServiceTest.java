package com.madhumita.dispatch.service;

import com.madhumita.dispatch.domain.*;
import com.madhumita.dispatch.repository.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchServiceTest {
    private DispatchService serviceWith(List<DeliveryOrder> orders, List<Vehicle> vehicles) {
        DeliveryOrderRepository orderRepository = mock(DeliveryOrderRepository.class);
        VehicleRepository vehicleRepository = mock(VehicleRepository.class);
        when(orderRepository.findAll()).thenReturn(orders);
        when(vehicleRepository.findAll()).thenReturn(vehicles);
        DistanceCalculator calculator = new DistanceCalculator();
        return new DispatchServiceImpl(orderRepository, vehicleRepository, new DispatchOptimizer(calculator));
    }

    @Test void assignsHigherPriorityBeforeLowerPriorityWhenCapacityIsLimited() {
        DispatchService service = serviceWith(
                List.of(new DeliveryOrder("LOW-1", 12, 77, "Low", 6, Priority.LOW),
                        new DeliveryOrder("HIGH-1", 12, 77, "High", 6, Priority.HIGH)),
                List.of(new Vehicle("V-1", 10, 12, 77, "Depot")));

        var result = service.buildPlan();

        assertEquals(List.of("HIGH-1"), result.dispatchPlan().get(0).assignedOrders().stream().map(o -> o.orderId()).toList());
        assertEquals(6.0, result.dispatchPlan().get(0).totalLoad());
        assertEquals(List.of("LOW-1"), result.unassignedOrders().stream().map(o -> o.orderId()).toList());
    }

    @Test void findsGlobalMinimumInsteadOfGreedyNearestFirstAssignment() {
        // Greedy would send O-1 to V-1 (4 km), forcing O-2 to V-2 (about 10 km).
        // The global optimum sends O-1 to V-2 (about 6 km) and O-2 to V-1 (0 km).
        DispatchService service = serviceWith(
                List.of(new DeliveryOrder("O-1", 0, 4.0 / 111.195, "First", 10, Priority.HIGH),
                        new DeliveryOrder("O-2", 0, 0, "Second", 10, Priority.HIGH)),
                List.of(new Vehicle("V-1", 10, 0, 0, "Depot 1"),
                        new Vehicle("V-2", 10, 0, 10.0 / 111.195, "Depot 2")));

        var result = service.buildPlan();
        var first = result.dispatchPlan().stream().filter(p -> p.assignedOrders().stream().anyMatch(o -> o.orderId().equals("O-1"))).findFirst().orElseThrow();
        var second = result.dispatchPlan().stream().filter(p -> p.assignedOrders().stream().anyMatch(o -> o.orderId().equals("O-2"))).findFirst().orElseThrow();

        assertEquals("V-2", first.vehicleId());
        assertEquals("V-1", second.vehicleId());
        assertTrue(result.totalAssignedDistanceKm() < 7.0, "The global plan should be approximately 6 km, not 14 km");
    }

    @Test void neverExceedsVehicleCapacityAndReportsUnassignableOrders() {
        DispatchService service = serviceWith(
                List.of(new DeliveryOrder("HEAVY", 0, 0, "Drop", 50, Priority.HIGH)),
                List.of(new Vehicle("V1", 10, 0, 0, "Depot")));
        var result = service.buildPlan();
        assertEquals(1, result.unassignedOrders().size());
        assertTrue(result.unassignedOrders().get(0).reason().contains("exceeds the capacity"));
        assertEquals(0.0, result.dispatchPlan().get(0).totalLoad());
    }

    @Test void returnsAllOrdersAsUnassignedWhenNoVehiclesExist() {
        DispatchService service = serviceWith(List.of(new DeliveryOrder("O1", 0, 0, "A", 1, Priority.MEDIUM)), List.of());
        var result = service.buildPlan();
        assertTrue(result.dispatchPlan().isEmpty());
        assertEquals(1, result.unassignedOrders().size());
        assertTrue(result.unassignedOrders().get(0).reason().contains("No vehicles"));
    }

    @Test void handlesNoOrdersAndStillReturnsRegisteredVehicles() {
        DispatchService service = serviceWith(List.of(), List.of(new Vehicle("V1", 10, 0, 0, "Depot")));
        var result = service.buildPlan();
        assertEquals(1, result.dispatchPlan().size());
        assertTrue(result.dispatchPlan().get(0).assignedOrders().isEmpty());
        assertEquals(0.0, result.totalAssignedDistanceKm());
    }

    @Test void distanceIsTheRealRouteBetweenDropsNotTheSumOfStraightLinesFromTheVehicle() {
        // Vehicle at 0,0; drops 0.1 and 0.2 degrees east on the equator (~11.12 km and ~22.24 km away).
        // Route: start -> near (11.12 km) -> far (11.12 km) = 22.24 km, NOT 11.12 + 22.24 = 33.36 km.
        DispatchService service = serviceWith(
                List.of(new DeliveryOrder("NEAR", 0, 0.1, "Near drop", 1, Priority.HIGH),
                        new DeliveryOrder("FAR", 0, 0.2, "Far drop", 1, Priority.HIGH)),
                List.of(new Vehicle("V-1", 10, 0, 0, "Depot")));

        var vehicle = service.buildPlan().dispatchPlan().get(0);

        assertEquals(List.of("NEAR", "FAR"), vehicle.assignedOrders().stream().map(o -> o.orderId()).toList());
        assertEquals(List.of(1, 2), vehicle.assignedOrders().stream().map(o -> o.stopNumber()).toList());
        assertEquals(11.12, vehicle.assignedOrders().get(0).distanceFromPreviousKm(), 0.01);
        assertEquals(11.12, vehicle.assignedOrders().get(1).distanceFromPreviousKm(), 0.01);
        assertEquals(22.24, vehicle.totalDistanceKm(), 0.01);
        assertEquals(vehicle.assignedOrders().get(1).cumulativeDistanceKm(), vehicle.totalDistanceKm(), 1e-9);
    }

    @Test void ordersAtTheSameDropPointAreDrivenToOnlyOnce() {
        DispatchService service = serviceWith(
                List.of(new DeliveryOrder("A", 0, 0.1, "Same drop", 1, Priority.HIGH),
                        new DeliveryOrder("B", 0, 0.1, "Same drop", 1, Priority.LOW)),
                List.of(new Vehicle("V-1", 10, 0, 0, "Depot")));

        var vehicle = service.buildPlan().dispatchPlan().get(0);

        assertEquals(11.12, vehicle.totalDistanceKm(), 0.01);
        assertEquals(0.0, vehicle.assignedOrders().get(1).distanceFromPreviousKm());
        assertEquals("A", vehicle.assignedOrders().get(0).orderId(), "HIGH priority is delivered first at a shared drop");
    }

    @Test void vehicleTotalsAndFleetTotalAreConsistentWithTheLegs() {
        DispatchService service = serviceWith(
                List.of(new DeliveryOrder("O1", 0, 0.1, "A", 4, Priority.HIGH),
                        new DeliveryOrder("O2", 0, 0.3, "B", 4, Priority.MEDIUM),
                        new DeliveryOrder("O3", 0.2, 0, "C", 4, Priority.LOW)),
                List.of(new Vehicle("V-1", 8, 0, 0, "D1"), new Vehicle("V-2", 8, 0.2, 0.2, "D2")));
        var plan = service.buildPlan();

        double fleet = 0;
        for (var v : plan.dispatchPlan()) {
            double legs = v.assignedOrders().stream().mapToDouble(o -> o.distanceFromPreviousKm()).sum();
            assertEquals(legs, v.totalDistanceKm(), 0.0015);
            assertTrue(v.totalLoad() <= v.capacity());
            assertEquals(v.capacity() - v.totalLoad(), v.remainingCapacity(), 1e-9);
            fleet += v.totalDistanceKm();
        }
        assertEquals(fleet, plan.totalAssignedDistanceKm(), 0.0015);
    }
}
