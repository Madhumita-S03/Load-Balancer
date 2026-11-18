package com.madhumita.dispatch.service;

import com.madhumita.dispatch.domain.DeliveryOrder;
import com.madhumita.dispatch.domain.Priority;
import com.madhumita.dispatch.domain.Vehicle;
import com.madhumita.dispatch.dto.*;
import com.madhumita.dispatch.repository.DeliveryOrderRepository;
import com.madhumita.dispatch.repository.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class DispatchUpsertTest {
    @Test
    void updatesExistingOrderByOrderIdWithoutDeletingRecords() {
        DeliveryOrderRepository orders = mock(DeliveryOrderRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        DeliveryOrder existing = new DeliveryOrder("ORD-1", 10, 20, "Old address", 5, Priority.LOW);
        when(orders.findAllByOrderIdIn(anyCollection())).thenReturn(List.of(existing));
        DispatchService service = new DispatchServiceImpl(orders, vehicles, new DispatchOptimizer(new DistanceCalculator()));

        service.upsertOrders(new OrdersRequest(List.of(
                new OrderInput("ORD-1", 11.0, 21.0, "New address", 7.0, Priority.HIGH))));

        assertEquals(11, existing.getLatitude());
        assertEquals(21, existing.getLongitude());
        assertEquals("New address", existing.getAddress());
        assertEquals(7, existing.getPackageWeight());
        assertEquals(Priority.HIGH, existing.getPriority());
        verify(orders, never()).deleteAllInBatch();
        verify(orders).saveAll(List.of(existing));
    }

    @Test
    void insertsNewVehicleAndDoesNotDeleteExistingFleet() {
        DeliveryOrderRepository orders = mock(DeliveryOrderRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        when(vehicles.findAllByVehicleIdIn(anyCollection())).thenReturn(List.of());
        DispatchService service = new DispatchServiceImpl(orders, vehicles, new DispatchOptimizer(new DistanceCalculator()));
        VehicleInput input = new VehicleInput("V-1", 80.0, 12.9, 77.6, "Indiranagar, Bangalore, Karnataka, India");

        service.upsertVehicles(List.of(input));

        verify(vehicles, never()).deleteAllInBatch();
        // saveAll accepts Iterable, so capture it as Iterable rather than assuming List methods.
        ArgumentCaptor<Iterable<Vehicle>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(vehicles).saveAll(captor.capture());
        List<Vehicle> saved = new java.util.ArrayList<>();
        captor.getValue().forEach(saved::add);
        assertEquals(1, saved.size());
        assertEquals("V-1", saved.get(0).getVehicleId());
        assertEquals(12.9, saved.get(0).getCurrentLatitude(), 0.000001);
    }

    @Test
    void rejectsDuplicateIdsAfterTrimmingWhitespace() {
        DeliveryOrderRepository orders = mock(DeliveryOrderRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        DispatchService service = new DispatchServiceImpl(orders, vehicles, new DispatchOptimizer(new DistanceCalculator()));
        OrdersRequest request = new OrdersRequest(List.of(
                new OrderInput("ORD-1", 10.0, 20.0, "A", 1.0, Priority.HIGH),
                new OrderInput(" ORD-1 ", 10.0, 20.0, "B", 1.0, Priority.LOW)));

        assertThrows(IllegalArgumentException.class, () -> service.upsertOrders(request));
        verify(orders, never()).saveAll(any());
    }
}
