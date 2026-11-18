package com.madhumita.dispatch.service;

import com.madhumita.dispatch.dto.*;
import java.util.List;

/** Application service contract used by the API layer. */
public interface DispatchService {
    ApiMessage upsertOrders(OrdersRequest request);
    ApiMessage upsertVehicles(List<VehicleInput> vehicles);
    DispatchPlanResponse buildPlan();
}
