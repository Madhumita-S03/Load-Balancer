package com.madhumita.dispatch.api;

import com.madhumita.dispatch.dto.*;
import com.madhumita.dispatch.service.DispatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dispatch")
@Tag(name = "Dispatch", description = "Register delivery orders and fleet vehicles, then generate a capacity-aware dispatch plan.")
public class DispatchController {
    private static final String ORDERS_EXAMPLE = """
            [
              {"orderId":"ORD001","latitude":12.9716,"longitude":77.5946,
               "address":"MG Road, Bangalore, Karnataka, India","packageWeight":12.5,"priority":"HIGH"}
            ]""";
    private static final String VEHICLES_EXAMPLE = """
            [
              {"vehicleId":"VEH001","capacity":100,"currentLatitude":28.7041,"currentLongitude":77.1025,
               "currentAddress":"Karol Bagh, Delhi, India"},
              {"vehicleId":"VEH002","capacity":80,"currentLatitude":28.5355,"currentLongitude":77.3910,
               "currentAddress":"Sector 18, Noida, Uttar Pradesh, India"}
            ]""";

    private final DispatchService dispatchService;
    private final RequestBodyParser bodyParser;

    public DispatchController(DispatchService dispatchService, RequestBodyParser bodyParser) {
        this.dispatchService = dispatchService;
        this.bodyParser = bodyParser;
    }

    @PostMapping(value = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Register delivery orders",
            description = "Creates or updates orders by orderId. Send a JSON array (preferred) or {\"orders\":[...]}. "
                    + "Existing records omitted from the request are retained; IDs must be unique within the request.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = OrderInput.class)),
                    examples = @ExampleObject(name = "Orders", value = ORDERS_EXAMPLE))))
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Orders accepted and upserted",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiMessage.class))),
            @ApiResponse(responseCode = "400", description = "Malformed JSON, missing/invalid fields or duplicate orderId",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Concurrent update conflict; retry",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))})
    public ApiMessage addOrders(@RequestBody String body) {
        return dispatchService.upsertOrders(new OrdersRequest(bodyParser.parseList(body, OrderInput.class, "orders")));
    }

    @PostMapping(value = "/vehicles", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Register fleet vehicles",
            description = "Creates or updates vehicles by vehicleId. Send a JSON array (preferred) or {\"vehicles\":[...]}. "
                    + "Existing records omitted from the request are retained; IDs must be unique within the request.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = VehicleInput.class)),
                    examples = @ExampleObject(name = "Vehicles", value = VEHICLES_EXAMPLE))))
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Vehicles accepted and upserted",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiMessage.class))),
            @ApiResponse(responseCode = "400", description = "Malformed JSON, missing/invalid fields or duplicate vehicleId",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Concurrent update conflict; retry",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))})
    public ApiMessage addVehicles(@RequestBody String body) {
        return dispatchService.upsertVehicles(bodyParser.parseList(body, VehicleInput.class, "vehicles"));
    }

    @GetMapping(value = "/plan", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate priority-first dispatch plan", description = "Maximizes assigned HIGH orders, then MEDIUM, then LOW (capacity-feasible), then minimizes total route distance. Each vehicle drives start -> drop 1 -> drop 2 -> ... (no return trip); every leg is a Haversine distance, and assignedOrders are listed in driving order. Distances are straight-line, not road distances.")
    @ApiResponse(responseCode = "200", description = "Dispatch plan with per-vehicle assignments and unassigned orders")
    public DispatchPlanResponse getPlan() { return dispatchService.buildPlan(); }
}
