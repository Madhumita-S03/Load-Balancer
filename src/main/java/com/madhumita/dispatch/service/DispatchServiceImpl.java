package com.madhumita.dispatch.service;

import com.madhumita.dispatch.domain.*;
import com.madhumita.dispatch.dto.*;
import com.madhumita.dispatch.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/** Coordinates persistence and the pure dispatch optimization workflow. */
@Service
public class DispatchServiceImpl implements DispatchService {
    private final DeliveryOrderRepository orderRepository;
    private final VehicleRepository vehicleRepository;
    private final DispatchOptimizer optimizer;

    public DispatchServiceImpl(DeliveryOrderRepository orderRepository, VehicleRepository vehicleRepository,
                           DispatchOptimizer optimizer) {
        this.orderRepository = orderRepository;
        this.vehicleRepository = vehicleRepository;
        this.optimizer = optimizer;
    }

    /** Inserts new orders and updates matching orderId records without deleting omitted records. */
    @Override
    @Transactional
    public ApiMessage upsertOrders(OrdersRequest request) {
        List<OrderInput> inputs = request.orders();
        ensureUnique(inputs.stream().map(o -> o.orderId().trim()).toList(), "orderId");
        if (inputs.isEmpty()) return new ApiMessage("No orders supplied; existing records were retained.", "success");

        List<String> ids = inputs.stream().map(o -> o.orderId().trim()).toList();
        Map<String, DeliveryOrder> existing = orderRepository.findAllByOrderIdIn(ids).stream()
                .collect(Collectors.toMap(DeliveryOrder::getOrderId, o -> o));
        List<DeliveryOrder> toSave = new ArrayList<>();
        for (OrderInput input : inputs) {
            String id = input.orderId().trim();
            DeliveryOrder incoming = new DeliveryOrder(id, input.latitude(), input.longitude(),
                    input.address().trim(), input.packageWeight(), input.priority());
            DeliveryOrder current = existing.get(id);
            if (current == null) toSave.add(incoming);
            else { current.updateFrom(incoming); toSave.add(current); }
        }
        orderRepository.saveAll(toSave);
        return new ApiMessage("Delivery orders upserted successfully.", "success");
    }

    /** Inserts new vehicles and updates matching vehicleId records without deleting omitted records. */
    @Override
    @Transactional
    public ApiMessage upsertVehicles(List<VehicleInput> inputs) {
        ensureUnique(inputs.stream().map(v -> v.vehicleId().trim()).toList(), "vehicleId");
        if (inputs.isEmpty()) return new ApiMessage("No vehicles supplied; existing records were retained.", "success");

        List<String> ids = inputs.stream().map(v -> v.vehicleId().trim()).toList();
        Map<String, Vehicle> existing = vehicleRepository.findAllByVehicleIdIn(ids).stream()
                .collect(Collectors.toMap(Vehicle::getVehicleId, v -> v));
        List<Vehicle> toSave = new ArrayList<>();
        for (VehicleInput input : inputs) {
            String id = input.vehicleId().trim();
            Vehicle incoming = new Vehicle(id, input.capacity(), input.currentLatitude(),
                    input.currentLongitude(), input.currentAddress().trim());
            Vehicle current = existing.get(id);
            if (current == null) toSave.add(incoming);
            else { current.updateFrom(incoming); toSave.add(current); }
        }
        vehicleRepository.saveAll(toSave);
        return new ApiMessage("Vehicles upserted successfully.", "success");
    }

    /**
     * Builds the dispatch plan. Each vehicle gets an ordered route (start -> drop 1 -> drop 2 ...);
     * all distances are Haversine kilometres rounded to 3 decimals, and every total is the exact sum
     * of the rounded legs so the response can be audited by hand.
     */
    @Override
    @Transactional(readOnly = true)
    public DispatchPlanResponse buildPlan() {
        List<DeliveryOrder> orders = orderRepository.findAll();
        List<Vehicle> vehicles = vehicleRepository.findAll().stream()
                .sorted(Comparator.comparing(Vehicle::getVehicleId)).toList();
        Map<String, DeliveryOrder> orderById = orders.stream()
                .collect(Collectors.toMap(DeliveryOrder::getOrderId, o -> o));

        DispatchOptimizer.OptimizationResult optimized = optimizer.optimize(orders, vehicles);

        Set<String> assignedIds = new HashSet<>();
        List<VehiclePlanResponse> result = new ArrayList<>();
        double totalDistance = 0.0;
        double totalLoad = 0.0;
        for (Vehicle vehicle : vehicles) {
            DispatchOptimizer.VehicleRoute route = optimized.routeByVehicleId().get(vehicle.getVehicleId());
            List<AssignedOrderResponse> stops = new ArrayList<>();
            double load = 0.0;
            double cumulative = 0.0;
            if (route != null) {
                int stopNumber = 1;
                for (DispatchOptimizer.RouteLeg leg : route.legs()) {
                    DeliveryOrder order = orderById.get(leg.orderId());
                    double legKm = round3(leg.distanceFromPreviousKm());
                    cumulative = round3(cumulative + legKm);
                    load += order.getPackageWeight();
                    assignedIds.add(order.getOrderId());
                    stops.add(new AssignedOrderResponse(stopNumber++, order.getOrderId(), order.getLatitude(),
                            order.getLongitude(), order.getAddress(), order.getPackageWeight(),
                            order.getPriority(), legKm, cumulative));
                }
            }
            result.add(new VehiclePlanResponse(vehicle.getVehicleId(), vehicle.getCapacity(),
                    vehicle.getCurrentLatitude(), vehicle.getCurrentLongitude(), vehicle.getCurrentAddress(),
                    load, vehicle.getCapacity() - load, cumulative, List.copyOf(stops)));
            totalDistance = round3(totalDistance + cumulative);
            totalLoad += load;
        }

        double maxCapacity = vehicles.stream().mapToDouble(Vehicle::getCapacity).max().orElse(0.0);
        List<UnassignedOrderResponse> unassigned = new ArrayList<>();
        for (DeliveryOrder order : orders.stream()
                .sorted(Comparator.comparingInt((DeliveryOrder o) -> o.getPriority().getRank())
                        .thenComparing(DeliveryOrder::getOrderId)).toList()) {
            if (assignedIds.contains(order.getOrderId())) continue;
            String reason = vehicles.isEmpty() ? "No vehicles have been registered."
                    : order.getPackageWeight() > maxCapacity
                    ? "Order weight exceeds the capacity of every registered vehicle."
                    : order.getPriority() == com.madhumita.dispatch.domain.Priority.LOW
                    ? "Low-priority order not dispatched: no spare capacity, or delivering it would add more extra driving than the low-priority detour limit."
                    : "No feasible capacity remains under the priority-first allocation objective.";
            unassigned.add(new UnassignedOrderResponse(order.getOrderId(), order.getPackageWeight(),
                    order.getPriority(), reason));
        }
        return new DispatchPlanResponse(result, unassigned, totalDistance, totalLoad);
    }

    private static double round3(double value) { return Math.round(value * 1000.0) / 1000.0; }

    private static void ensureUnique(List<String> values, String field) {
        Set<String> normalized = values.stream().map(String::trim).collect(Collectors.toSet());
        if (normalized.size() != values.size()) throw new IllegalArgumentException("Duplicate " + field + " values are not allowed.");
    }
}
