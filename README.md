# Dispatch Load Balancer

Java / Spring Boot REST API that stores delivery orders and fleet vehicles in PostgreSQL and creates a priority-aware, capacity-constrained dispatch plan using Haversine great-circle distances.

## Technology stack

- Java 17
- Spring Boot 3.5.6
- Spring Web and Jakarta Bean Validation
- Spring Data JPA / Hibernate
- PostgreSQL
- springdoc-openapi / Swagger UI
- JUnit 5, Mockito, Spring MockMvc and H2 for isolated integration tests
- JaCoCo coverage report

The application is tested through REST endpoints, Swagger and Postman.

## Project structure

```text
src/main/java/com/madhumita/dispatch/
├── api/          # REST controller and centralized HTTP error handling
├── domain/       # JPA entities and priority enum
├── dto/          # Request and response records (API contracts)
├── repository/   # Spring Data JPA repositories
└── service/      # Persistence workflow, Haversine utility and optimizer

src/main/resources/
├── application.properties
└── db/migration/ # Versioned PostgreSQL schema migrations

src/test/java/    # Unit and HTTP/database integration tests
sample-data/      # Example order/vehicle payloads and Postman collection
```
## Design Patterns & Algorithms

### Design Patterns

- **Dependency Injection:** `DispatchService` and `DispatchServiceImpl` – The controller depends on the service interface, keeping it loosely coupled to the implementation.
- **Centralized Exception Handling:** `@RestControllerAdvice` (`GlobalExceptionHandler`) – Handles application errors centrally and returns consistent JSON error responses with appropriate HTTP status codes.
- **Singleton Pattern:** Spring-managed `@Component` and `@Service` beans – Spring maintains shared bean instances. The optimizer is designed without request-specific shared mutable state.

### Algorithms & Optimization Techniques

- **Branch and Bound:** Explores feasible order allocations while pruning branches that cannot improve the current solution.
- **Simulated Annealing:** Explores alternative order assignments to escape local optima.
- **Held-Karp Algorithm:** Finds an exact shortest route for a small number of distinct delivery locations.
- **Nearest Neighbour & 2-opt:** Builds and improves routes for larger numbers of delivery locations.
- **Route Cost Caching:** Reuses previously calculated route costs to avoid redundant computations.

## Swagger / OpenAPI

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

### Generate the dispatch plan`

The response contains:

- Every registered vehicle, including ID, capacity, current coordinates and current address.
- Each assigned order's stop number (driving order), ID, coordinates, address, package weight and priority, plus the km driven from the previous stop and the cumulative km.
- Per-vehicle total load, remaining capacity and total route distance.
- Overall assigned load and overall assigned distance.
- Unassigned orders with a human-readable reason.

Vehicles with no assigned orders remain in the response with zero load and zero distance. If no vehicles are registered, all saved orders are returned as unassigned. If no orders are registered, an empty plan is returned for each registered vehicle.

## Allocation objective and explicit assumptions

The requirement says high-priority orders must be assigned first and total travel distance minimized. To make this unambiguous and testable, the implementation uses this lexicographic objective:

1. Maximize the number of assigned `HIGH` orders.
2. Among plans with the same number of assigned `HIGH` orders, maximize assigned `MEDIUM` orders.
3. Among plans with the same HIGH and MEDIUM counts, `LOW` orders are optional: a LOW order is delivered only if it adds less than `dispatch.low-priority-max-detour-km` (default 50 km, env `DISPATCH_LOW_PRIORITY_MAX_DETOUR_KM`) of extra driving. This stops a vehicle being sent hundreds of km away just to drop a low-priority parcel.
4. Minimize total Haversine route distance, counting each delivered LOW order as a credit of the detour limit (see below).

This means a lower-priority order cannot displace a feasible higher-priority order merely to reduce distance. Within each priority tier, the solver can choose a globally better combination of vehicle assignments instead of committing to the nearest vehicle for each order one at a time.

### Distance definition (route based)

Every vehicle drives one **open route**: it starts at its current latitude/longitude, visits its assigned drop points in the best order, and stops at the last drop (no return trip). Each leg - vehicle to first drop, drop to next drop, and so on - is a Haversine distance, so:

- `distanceFromPreviousKm` is the leg from the previous stop (or the vehicle's start for stop 1);
- `totalDistanceKm` of a vehicle is the length of its whole route (sum of its legs);
- `totalAssignedDistanceKm` is the sum over all vehicles.

Orders sharing the same coordinates are one drop point: the vehicle drives there once (later orders at that point add 0 km, HIGH priority is listed first). Distances are straight-line, not road distances. All distances are rounded to 3 decimals and totals are sums of the rounded legs, so the response can be checked by hand.

### Optimization approach

1. **Priority counts (branch-and-bound).** Finds the best achievable counts - most HIGH, then most MEDIUM, then most LOW - subject to vehicle capacities. Uses per-tier capacity upper bounds, memoization on remaining capacities and symmetry pruning, with a node budget so a request cannot run unbounded.
2. **Route distance (local search).** Starting from that assignment, relocate / swap / replace-with-unassigned moves inside a seeded simulated-annealing loop, followed by hill-climbing, minimise the total route length without ever lowering the priority counts. Each vehicle's drop order is solved exactly (Held-Karp) for up to 9 distinct drop points; bigger routes use nearest-neighbour + 2-opt.

## Haversine formula

For two latitude/longitude points, the implementation converts degrees to radians and calculates:

```text
a = sin²(Δφ / 2) + cos(φ1) × cos(φ2) × sin²(Δλ / 2)
c = 2 × atan2(√a, √(1 - a))
distance = EarthRadius × c
```

Earth radius is `6371.0088 km` (mean Earth radius). The intermediate value `a` is clamped to `[0, 1]` to avoid floating-point rounding errors near antipodal points.

## Validation and error handling

- `orderId` and `vehicleId` are required, nonblank and limited to 100 characters.
- Addresses are required, nonblank and limited to 500 characters.
- Latitude is required and must be between -90 and 90 inclusive.
- Longitude is required and must be between -180 and 180 inclusive.
- Package weight and vehicle capacity are required and must be greater than zero.
- Priority is required and must be `HIGH`, `MEDIUM` or `LOW`.
- Missing numeric fields are rejected (nullable `Double` request fields plus `@NotNull` prevent Java's primitive zero default from hiding omitted JSON values).
- Duplicate business IDs within one request are rejected.
- Malformed JSON and invalid enum values return HTTP 400 with an explanatory message (for malformed JSON: line and column of the problem; for bad values: the exact field, e.g. `vehicles[2].capacity`).
- Request bodies are read by `RequestBodyParser`, which tolerates content produced by copy/paste (non-breaking spaces, BOM, typographic quotes) and accepts `application/json`, `text/plain` or no content type, so Swagger UI and Postman behave identically.
- Unexpected server errors return a structured HTTP 500 body (details are logged, not leaked); Spring's own 404/405/415 keep their status.
- Database uniqueness or optimistic-lock conflicts return HTTP 409.
- An order heavier than every vehicle, or one that cannot be fitted after the higher-priority allocation objective, is returned as unassigned rather than causing a server error.


