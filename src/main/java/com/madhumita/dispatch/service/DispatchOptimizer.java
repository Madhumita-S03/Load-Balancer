package com.madhumita.dispatch.service;

import com.madhumita.dispatch.domain.DeliveryOrder;
import com.madhumita.dispatch.domain.Vehicle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Priority-first, distance-minimising dispatch optimizer.
 *
 * <p><b>Distance model.</b> Each vehicle drives an open route: it starts at its current position, visits
 * its assigned drop points in the best order, and stops at the last drop (no return trip). Every leg
 * (vehicle to first drop, drop to next drop, ...) is a Haversine distance, so the vehicle total is the real
 * length of that route and the fleet total is the sum of the vehicle routes.
 *
 * <p><b>Objective, in strict order.</b>
 * <ol>
 *   <li>Maximize the number of assigned HIGH orders.</li>
 *   <li>Then maximize assigned MEDIUM orders.</li>
 *   <li>Then minimize (total route distance - lowPriorityDetourKm x assigned LOW orders). In words: a LOW order
 *       is delivered only if it costs less than lowPriorityDetourKm of extra driving, so a vehicle is never
 *       sent hundreds of km away just to drop a low-priority parcel.</li>
 * </ol>
 *
 * <p><b>Method.</b>
 * <ol>
 *   <li>Stage 1 - branch-and-bound over capacities finds the best achievable priority counts
 *       (distance ignored). Search effort is bounded; if the bound is hit the best plan found is used.</li>
 *   <li>Stage 2 - local search (relocate, swap, replace-with-unassigned moves inside a seeded simulated-annealing loop, then hill-climb)
 *       lowers total route distance without ever lowering the priority counts. Routes of up to 9 distinct
 *       drop locations are ordered exactly (Held-Karp); larger routes use nearest-neighbour + 2-opt.</li>
 * </ol>
 * Priority counts are optimal (within the search bound); route distance comes from a strong, deterministic
 * heuristic because vehicle routing is NP-hard.
 */
@Component
public class DispatchOptimizer {
    private static final int EXACT_ROUTE_MAX_STOPS = 9;
    private static final long COUNT_SEARCH_NODE_BUDGET = 3_000_000L;
    private static final long UNITS_PER_KG = 1_000_000L;
    private static final long MAX_UNITS = 1L << 50;
    private static final int ANNEAL_RUNS = 24;
    private static final int ANNEAL_ITERATIONS = 120_000;
    private static final long EVALUATION_BUDGET_SMALL = 40_000_000L; // up to 60 orders
    private static final long EVALUATION_BUDGET_LARGE = 3_000_000L;  // keeps big requests responsive
    private static final long SEED = 20260618L;
    private static final double EPS = 1e-9;

    public static final double DEFAULT_LOW_PRIORITY_DETOUR_KM = 50.0;

    private final DistanceCalculator distanceCalculator;
    private final double lowPriorityDetourKm;

    public DispatchOptimizer(DistanceCalculator distanceCalculator) {
        this(distanceCalculator, DEFAULT_LOW_PRIORITY_DETOUR_KM);
    }

    @Autowired
    public DispatchOptimizer(DistanceCalculator distanceCalculator,
                             @Value("${dispatch.low-priority-max-detour-km:50.0}") double lowPriorityDetourKm) {
        this.distanceCalculator = distanceCalculator;
        this.lowPriorityDetourKm = Math.max(0.0, lowPriorityDetourKm);
    }

    public OptimizationResult optimize(List<DeliveryOrder> inputOrders, List<Vehicle> inputVehicles) {
        if (inputOrders.isEmpty() || inputVehicles.isEmpty()) {
            return new OptimizationResult(Map.of(), List.of(0, 0, 0), 0.0);
        }
        Problem problem = new Problem(inputOrders, inputVehicles);
        CountSearch counts = new CountSearch(problem);
        counts.run();
        return new LocalSearch(problem, counts.bestAssign, counts.bestCounts).run();
    }

    /** One leg of a vehicle route: the order delivered and the km driven since the previous stop. */
    public record RouteLeg(String orderId, double distanceFromPreviousKm) { }

    /** Ordered route of one vehicle. totalDistanceKm is the sum of its legs. */
    public record VehicleRoute(String vehicleId, List<RouteLeg> legs, double totalDistanceKm) { }

    public record OptimizationResult(Map<String, VehicleRoute> routeByVehicleId,
                                     List<Integer> assignedCountsByPriority,
                                     double totalDistanceKm) { }

    // ------------------------------------------------------------------------------------------
    // Problem data
    // ------------------------------------------------------------------------------------------
    private final class Problem {
        final DeliveryOrder[] orders;
        final Vehicle[] vehicles;
        final int n, m;
        final long[] weight;        // scaled units
        final long[] capacity;      // scaled units
        final int[] rank;           // priority rank per order
        final int[] stopOf;         // distinct drop-location id per order
        final int stopCount;
        final double[][] stopDist;  // drop to drop (Haversine)
        final double[][] startDist; // vehicle start to drop (Haversine)

        Problem(List<DeliveryOrder> inputOrders, List<Vehicle> inputVehicles) {
            orders = inputOrders.stream().sorted(Comparator
                    .comparingInt((DeliveryOrder o) -> o.getPriority().getRank())
                    .thenComparing(Comparator.comparingDouble(DeliveryOrder::getPackageWeight).reversed())
                    .thenComparing(DeliveryOrder::getOrderId)).toArray(DeliveryOrder[]::new);
            vehicles = inputVehicles.stream().sorted(Comparator.comparing(Vehicle::getVehicleId))
                    .toArray(Vehicle[]::new);
            n = orders.length;
            m = vehicles.length;
            weight = new long[n];
            rank = new int[n];
            stopOf = new int[n];
            capacity = new long[m];
            for (int v = 0; v < m; v++) capacity[v] = toUnits(vehicles[v].getCapacity());

            Map<String, Integer> stopIds = new LinkedHashMap<>();
            List<double[]> coords = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                weight[i] = toUnits(orders[i].getPackageWeight());
                rank[i] = orders[i].getPriority().getRank();
                String key = orders[i].getLatitude() + "," + orders[i].getLongitude();
                Integer id = stopIds.get(key);
                if (id == null) {
                    id = coords.size();
                    stopIds.put(key, id);
                    coords.add(new double[]{orders[i].getLatitude(), orders[i].getLongitude()});
                }
                stopOf[i] = id;
            }
            stopCount = coords.size();
            stopDist = new double[stopCount][stopCount];
            for (int a = 0; a < stopCount; a++)
                for (int b = a + 1; b < stopCount; b++) {
                    double d = distanceCalculator.haversineKm(coords.get(a)[0], coords.get(a)[1],
                            coords.get(b)[0], coords.get(b)[1]);
                    stopDist[a][b] = d;
                    stopDist[b][a] = d;
                }
            startDist = new double[m][stopCount];
            for (int v = 0; v < m; v++)
                for (int s = 0; s < stopCount; s++)
                    startDist[v][s] = distanceCalculator.haversineKm(vehicles[v].getCurrentLatitude(),
                            vehicles[v].getCurrentLongitude(), coords.get(s)[0], coords.get(s)[1]);
        }

        private static long toUnits(double kg) {
            return Math.min(Math.round(kg * UNITS_PER_KG), MAX_UNITS);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Stage 1: best priority counts (capacity only)
    // ------------------------------------------------------------------------------------------
    private static final class CountSearch {
        private final Problem p;
        private final int[] cur;
        private final long[] used;
        private final int[] curCounts = new int[3];
        private final int[][] remainingByRank;
        private final Map<String, int[]> seen = new HashMap<>();
        private long nodes;
        int[] bestAssign;
        int[] bestCounts = {-1, -1, -1};

        CountSearch(Problem p) {
            this.p = p;
            this.cur = new int[p.n];
            Arrays.fill(cur, -1);
            this.used = new long[p.m];
            this.remainingByRank = new int[p.n + 1][3];
            for (int i = p.n - 1; i >= 0; i--) {
                System.arraycopy(remainingByRank[i + 1], 0, remainingByRank[i], 0, 3);
                remainingByRank[i][p.rank[i]]++;
            }
            this.bestAssign = new int[p.n];
            Arrays.fill(bestAssign, -1);
        }

        void run() { dfs(0); }

        private void dfs(int i) {
            if (++nodes > COUNT_SEARCH_NODE_BUDGET) return;
            if (i == p.n) {
                if (compare(curCounts, bestCounts) > 0) {
                    bestCounts = curCounts.clone();
                    bestAssign = cur.clone();
                }
                return;
            }
            if (compare(optimisticCounts(i), bestCounts) <= 0) return;

            String key = stateKey(i);
            int[] prior = seen.get(key);
            if (prior != null && compare(curCounts, prior) <= 0) return;
            seen.put(key, curCounts.clone());

            Set<Long> triedRemaining = new HashSet<>();
            for (int v = 0; v < p.m; v++) {
                long remaining = p.capacity[v] - used[v];
                if (p.weight[i] > remaining || !triedRemaining.add(remaining)) continue;
                used[v] += p.weight[i];
                curCounts[p.rank[i]]++;
                cur[i] = v;
                dfs(i + 1);
                cur[i] = -1;
                curCounts[p.rank[i]]--;
                used[v] -= p.weight[i];
            }
            dfs(i + 1); // leave this order unassigned
        }

        /** Upper bound: per tier, as many remaining orders as fit by total free capacity (lightest first). */
        private int[] optimisticCounts(int index) {
            long free = 0;
            for (int v = 0; v < p.m; v++) free += p.capacity[v] - used[v];
            int[] result = curCounts.clone();
            for (int r = 0; r < 3; r++) {
                if (remainingByRank[index][r] == 0) continue;
                // Within a tier orders are sorted heaviest-first, so the lightest sit at the tier's tail.
                int end = tierEnd(r);
                long sum = 0;
                int fit = 0;
                for (int k = end - 1; k >= index && p.rank[k] == r; k--) {
                    sum += p.weight[k];
                    if (sum > free) break;
                    fit++;
                }
                result[r] += fit;
            }
            return result;
        }

        private int tierEnd(int r) {
            int e = p.n;
            while (e > 0 && p.rank[e - 1] > r) e--;
            return e;
        }

        private String stateKey(int i) {
            long[] rem = new long[p.m];
            for (int v = 0; v < p.m; v++) rem[v] = p.capacity[v] - used[v];
            Arrays.sort(rem);
            return i + ":" + Arrays.toString(rem);
        }
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        return 0;
    }

    // ------------------------------------------------------------------------------------------
    // Stage 2: minimise route distance without lowering priority counts
    // ------------------------------------------------------------------------------------------
    private final class LocalSearch {
        private final Problem p;
        private final Random random = new Random(SEED);
        private record RouteKey(int vehicle, BitSet stops) { }
        private final Map<RouteKey, Double> routeCache = new HashMap<>();
        private final int[] targetCounts;
        private final int[] startAssign;
        private long evaluations;
        private final long evaluationBudget;

        LocalSearch(Problem p, int[] startAssign, int[] targetCounts) {
            this.p = p;
            this.startAssign = startAssign;
            this.targetCounts = targetCounts;
            this.evaluationBudget = p.n <= 60 ? EVALUATION_BUDGET_SMALL : EVALUATION_BUDGET_LARGE;
        }

        OptimizationResult run() {
            State best = new State(startAssign.clone());
            hillClimb(best);

            State greedy = greedyStart();
            if (greedy != null) {
                hillClimb(greedy);
                if (better(greedy, best)) best = greedy;
            }
            for (int r = 0; r < ANNEAL_RUNS && evaluations < evaluationBudget; r++) {
                State candidate = anneal(best.copy());
                hillClimb(candidate);
                if (better(candidate, best)) best = candidate;
            }
            return buildResult(best);
        }

        private final class State {
            final int[] assign;
            final long[] used = new long[p.m];
            final double[] cost = new double[p.m];
            final int[] counts = new int[3];
            double total;

            State(int[] assign) {
                this.assign = assign;
                for (int i = 0; i < p.n; i++)
                    if (assign[i] >= 0) { used[assign[i]] += p.weight[i]; counts[p.rank[i]]++; }
                for (int v = 0; v < p.m; v++) { cost[v] = routeCost(v, assign, -1, -1); total += cost[v]; }
            }

            State copy() { return new State(assign.clone()); }
        }

        private double score(State s) { return s.total - lowPriorityDetourKm * s.counts[2]; }

        /** HIGH then MEDIUM counts are strict; LOW is traded against distance via the detour limit. */
        private int compareHighMedium(int[] a, int[] b) {
            if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
            return Integer.compare(a[1], b[1]);
        }

        private boolean better(State a, State b) {
            int c = compareHighMedium(a.counts, b.counts);
            return c > 0 || (c == 0 && score(a) < score(b) - EPS);
        }

        private State greedyStart() {
            int[] assign = new int[p.n];
            Arrays.fill(assign, -1);
            long[] used = new long[p.m];
            for (int i = 0; i < p.n; i++) {
                int bestV = -1;
                double bestDelta = Double.POSITIVE_INFINITY;
                for (int v = 0; v < p.m; v++) {
                    if (p.weight[i] > p.capacity[v] - used[v]) continue;
                    double delta = routeCost(v, assign, i, -1) - routeCost(v, assign, -1, -1);
                    if (delta < bestDelta - EPS) { bestDelta = delta; bestV = v; }
                }
                if (bestV >= 0) { assign[i] = bestV; used[bestV] += p.weight[i]; }
            }
            State s = new State(assign);
            return compareHighMedium(s.counts, targetCounts) == 0 ? s : null;
        }

        /** Simulated annealing over relocate/swap/replace moves; priority counts are never changed. */
        private State anneal(State s) {
            double scale = 0;
            int pairs = 0;
            for (int v = 0; v < p.m; v++)
                for (int st = 0; st < p.stopCount; st++) { scale += p.startDist[v][st]; pairs++; }
            scale = Math.max(1.0, scale / Math.max(1, pairs));
            double t0 = 0.25 * scale, t1 = 0.002 * scale;
            int[] bestAssign = s.assign.clone();
            double bestScore = score(s);
            int iterations = Math.max(5_000, Math.min(ANNEAL_ITERATIONS, 2_400_000 / Math.max(1, p.n)));
            for (int it = 0; it < iterations && evaluations < evaluationBudget; it++) {
                double temp = t0 * StrictMath.pow(t1 / t0, (double) it / iterations);
                double r = random.nextDouble();
                if (r < 0.35) annealRelocate(s, temp);
                else if (r < 0.55) annealSwap(s, temp);
                else if (r < 0.63) annealReplace(s, temp);
                else if (r < 0.78) annealGroupRelocate(s, temp);
                else if (r < 0.90) annealGroupSwap(s, temp);
                else annealLowToggle(s, temp);
                if (score(s) < bestScore - EPS) { bestScore = score(s); bestAssign = s.assign.clone(); }
            }
            return new State(bestAssign);
        }

        private boolean accept(double delta, double temp) {
            return delta < -EPS || random.nextDouble() < StrictMath.exp(-delta / temp);
        }

        private void annealRelocate(State s, double temp) {
            int i = random.nextInt(p.n);
            int v = s.assign[i];
            if (v < 0 || p.m < 2) return;
            int w = random.nextInt(p.m);
            if (w == v || p.weight[i] > p.capacity[w] - s.used[w]) return;
            double delta = routeCost(v, s.assign, -1, i) + routeCost(w, s.assign, i, -1) - s.cost[v] - s.cost[w];
            if (accept(delta, temp)) move(s, i, w);
        }

        private void annealSwap(State s, double temp) {
            int a = random.nextInt(p.n), b = random.nextInt(p.n);
            int v = s.assign[a], w = s.assign[b];
            if (v < 0 || w < 0 || v == w) return;
            if (p.weight[b] - p.weight[a] > p.capacity[v] - s.used[v]) return;
            if (p.weight[a] - p.weight[b] > p.capacity[w] - s.used[w]) return;
            double delta = routeCost(v, s.assign, b, a) + routeCost(w, s.assign, a, b) - s.cost[v] - s.cost[w];
            if (accept(delta, temp)) { move(s, a, -1); move(s, b, v); move(s, a, w); }
        }

        /** Orders of vehicle v that are delivered to drop point `stop`. */
        private List<Integer> group(State s, int v, int stop) {
            List<Integer> g = new ArrayList<>();
            for (int i = 0; i < p.n; i++) if (s.assign[i] == v && p.stopOf[i] == stop) g.add(i);
            return g;
        }

        private long weightOf(List<Integer> g) {
            long w = 0;
            for (int i : g) w += p.weight[i];
            return w;
        }

        /** Move every order at one drop point of vehicle v to vehicle w (lets a vehicle change destination). */
        private void annealGroupRelocate(State s, double temp) {
            int v = s.assign[random.nextInt(p.n)];
            if (v < 0 || p.m < 2) return;
            int w = random.nextInt(p.m);
            if (w == v) return;
            List<Integer> g = group(s, v, p.stopOf[randomOrderOf(s, v)]);
            if (weightOf(g) > p.capacity[w] - s.used[w]) return;
            int[] trial = s.assign.clone();
            for (int i : g) trial[i] = w;
            double delta = routeCost(v, trial, -1, -1) + routeCost(w, trial, -1, -1) - s.cost[v] - s.cost[w];
            if (accept(delta, temp)) for (int i : g) move(s, i, w);
        }

        /** Exchange the orders at one drop point of vehicle v with those at one drop point of vehicle w. */
        private void annealGroupSwap(State s, double temp) {
            int a = random.nextInt(p.n), b = random.nextInt(p.n);
            int v = s.assign[a], w = s.assign[b];
            if (v < 0 || w < 0 || v == w) return;
            List<Integer> ga = group(s, v, p.stopOf[a]), gb = group(s, w, p.stopOf[b]);
            if (weightOf(gb) - weightOf(ga) > p.capacity[v] - s.used[v]) return;
            if (weightOf(ga) - weightOf(gb) > p.capacity[w] - s.used[w]) return;
            int[] trial = s.assign.clone();
            for (int i : ga) trial[i] = w;
            for (int i : gb) trial[i] = v;
            double delta = routeCost(v, trial, -1, -1) + routeCost(w, trial, -1, -1) - s.cost[v] - s.cost[w];
            if (accept(delta, temp)) {
                for (int i : ga) move(s, i, -1);
                for (int i : gb) move(s, i, v);
                for (int i : ga) move(s, i, w);
            }
        }

        private int randomOrderOf(State s, int v) {
            List<Integer> mine = new ArrayList<>();
            for (int i = 0; i < p.n; i++) if (s.assign[i] == v) mine.add(i);
            return mine.get(random.nextInt(mine.size()));
        }

        /** Drop an assigned LOW order or add an unassigned one; accepted by distance saved/added vs the detour limit. */
        private void annealLowToggle(State s, double temp) {
            int i = random.nextInt(p.n);
            if (p.rank[i] != 2) return;
            int v = s.assign[i];
            if (v >= 0) {
                double delta = routeCost(v, s.assign, -1, i) - s.cost[v] + lowPriorityDetourKm;
                if (accept(delta, temp)) move(s, i, -1);
            } else {
                int w = random.nextInt(p.m);
                if (p.weight[i] > p.capacity[w] - s.used[w]) return;
                double delta = routeCost(w, s.assign, i, -1) - s.cost[w] - lowPriorityDetourKm;
                if (accept(delta, temp)) move(s, i, w);
            }
        }

        private void annealReplace(State s, double temp) {
            int a = random.nextInt(p.n), u = random.nextInt(p.n);
            int v = s.assign[a];
            if (v < 0 || s.assign[u] >= 0 || p.rank[u] != p.rank[a]) return;
            if (p.weight[u] - p.weight[a] > p.capacity[v] - s.used[v]) return;
            double delta = routeCost(v, s.assign, u, a) - s.cost[v];
            if (accept(delta, temp)) { move(s, a, -1); move(s, u, v); }
        }

        private void move(State s, int order, int toVehicle) {
            int from = s.assign[order];
            if (from >= 0) { s.used[from] -= p.weight[order]; s.counts[p.rank[order]]--; }
            if (toVehicle >= 0) { s.used[toVehicle] += p.weight[order]; s.counts[p.rank[order]]++; }
            s.assign[order] = toVehicle;
            if (from >= 0) s.cost[from] = routeCost(from, s.assign, -1, -1);
            if (toVehicle >= 0) s.cost[toVehicle] = routeCost(toVehicle, s.assign, -1, -1);
            s.total = 0;
            for (int v = 0; v < p.m; v++) s.total += s.cost[v];
        }

        private void hillClimb(State s) {
            boolean improved = true;
            while (improved && evaluations < evaluationBudget) {
                improved = tryRelocate(s) || trySwap(s) || tryReplaceWithUnassigned(s) || tryLowPriorityToggle(s);
            }
        }

        private boolean tryRelocate(State s) {
            for (int i = 0; i < p.n; i++) {
                int v = s.assign[i];
                if (v < 0) continue;
                double without = routeCost(v, s.assign, -1, i);
                for (int w = 0; w < p.m; w++) {
                    if (w == v || p.weight[i] > p.capacity[w] - s.used[w]) continue;
                    double delta = without + routeCost(w, s.assign, i, -1) - s.cost[v] - s.cost[w];
                    if (delta < -EPS) { move(s, i, w); return true; }
                }
            }
            return false;
        }

        private boolean trySwap(State s) {
            for (int a = 0; a < p.n; a++) {
                int v = s.assign[a];
                if (v < 0) continue;
                for (int b = a + 1; b < p.n; b++) {
                    int w = s.assign[b];
                    if (w < 0 || w == v) continue;
                    if (p.weight[b] - p.weight[a] > p.capacity[v] - s.used[v]) continue;
                    if (p.weight[a] - p.weight[b] > p.capacity[w] - s.used[w]) continue;
                    double newV = routeCost(v, s.assign, b, a);
                    double newW = routeCost(w, s.assign, a, b);
                    if (newV + newW - s.cost[v] - s.cost[w] < -EPS) {
                        move(s, a, -1);
                        move(s, b, v);
                        move(s, a, w);
                        return true;
                    }
                }
            }
            return false;
        }

        /** Drop a LOW order whose route cost exceeds the detour limit, or add one that costs less than it. */
        private boolean tryLowPriorityToggle(State s) {
            for (int i = 0; i < p.n; i++) {
                if (p.rank[i] != 2) continue;
                int v = s.assign[i];
                if (v >= 0) {
                    if (routeCost(v, s.assign, -1, i) - s.cost[v] + lowPriorityDetourKm < -EPS) { move(s, i, -1); return true; }
                } else {
                    for (int w = 0; w < p.m; w++) {
                        if (p.weight[i] > p.capacity[w] - s.used[w]) continue;
                        if (routeCost(w, s.assign, i, -1) - s.cost[w] - lowPriorityDetourKm < -EPS) { move(s, i, w); return true; }
                    }
                }
            }
            return false;
        }

        /** Swap an assigned order for an unassigned one of equal or higher priority (never lowers counts). */
        private boolean tryReplaceWithUnassigned(State s) {
            for (int u = 0; u < p.n; u++) {
                if (s.assign[u] >= 0) continue;
                for (int a = 0; a < p.n; a++) {
                    int v = s.assign[a];
                    if (v < 0 || p.rank[u] > p.rank[a]) continue;
                    if (p.weight[u] - p.weight[a] > p.capacity[v] - s.used[v]) continue;
                    boolean higher = p.rank[u] < p.rank[a];
                    double delta = routeCost(v, s.assign, u, a) - s.cost[v];
                    if (higher || delta < -EPS) {
                        move(s, a, -1);
                        move(s, u, v);
                        return true;
                    }
                }
            }
            return false;
        }

        /** Cost of vehicle v's route using assign[], optionally adding one order and/or removing one. */
        private double routeCost(int v, int[] assign, int add, int remove) {
            evaluations++;
            BitSet stops = new BitSet(p.stopCount);
            for (int i = 0; i < p.n; i++)
                if ((assign[i] == v && i != remove) || i == add) stops.set(p.stopOf[i]);
            if (stops.isEmpty()) return 0.0;
            RouteKey key = new RouteKey(v, stops);
            Double cached = routeCache.get(key);
            if (cached != null) return cached;
            double cost = orderStops(v, stops.stream().toArray()).cost();
            routeCache.put(key, cost);
            return cost;
        }

        private record Ordered(int[] order, double cost) { }

        private Ordered orderStops(int v, int[] stops) {
            if (stops.length == 1) return new Ordered(stops, p.startDist[v][stops[0]]);
            return stops.length <= EXACT_ROUTE_MAX_STOPS ? heldKarp(v, stops) : heuristicRoute(v, stops);
        }

        /** Exact shortest open path starting at the vehicle and visiting every stop once. */
        private Ordered heldKarp(int v, int[] stops) {
            int k = stops.length, full = 1 << k;
            double[][] dp = new double[full][k];
            int[][] parent = new int[full][k];
            for (double[] row : dp) Arrays.fill(row, Double.POSITIVE_INFINITY);
            for (int j = 0; j < k; j++) { dp[1 << j][j] = p.startDist[v][stops[j]]; parent[1 << j][j] = -1; }
            for (int mask = 1; mask < full; mask++)
                for (int last = 0; last < k; last++) {
                    double base = dp[mask][last];
                    if (base == Double.POSITIVE_INFINITY) continue;
                    for (int next = 0; next < k; next++) {
                        if ((mask & (1 << next)) != 0) continue;
                        int nm = mask | (1 << next);
                        double c = base + p.stopDist[stops[last]][stops[next]];
                        if (c < dp[nm][next] - EPS) { dp[nm][next] = c; parent[nm][next] = last; }
                    }
                }
            int bestLast = 0;
            for (int j = 1; j < k; j++) if (dp[full - 1][j] < dp[full - 1][bestLast] - EPS) bestLast = j;
            int[] order = new int[k];
            int mask = full - 1, cur = bestLast;
            for (int pos = k - 1; pos >= 0; pos--) {
                order[pos] = stops[cur];
                int prev = parent[mask][cur];
                mask &= ~(1 << cur);
                cur = prev;
            }
            return new Ordered(order, dp[full - 1][bestLast]);
        }

        private Ordered heuristicRoute(int v, int[] stops) {
            List<Integer> remaining = new ArrayList<>();
            for (int s : stops) remaining.add(s);
            List<Integer> route = new ArrayList<>();
            int at = -1;
            while (!remaining.isEmpty()) {
                int bestIdx = 0;
                double bestD = Double.POSITIVE_INFINITY;
                for (int idx = 0; idx < remaining.size(); idx++) {
                    double d = at < 0 ? p.startDist[v][remaining.get(idx)] : p.stopDist[at][remaining.get(idx)];
                    if (d < bestD - EPS) { bestD = d; bestIdx = idx; }
                }
                at = remaining.remove(bestIdx);
                route.add(at);
            }
            boolean improved = true;
            while (improved) {
                improved = false;
                double current = pathCost(v, route);
                for (int i = 0; i < route.size() - 1 && !improved; i++)
                    for (int j = i + 1; j < route.size() && !improved; j++) {
                        Collections.reverse(route.subList(i, j + 1));
                        if (pathCost(v, route) < current - EPS) improved = true;
                        else Collections.reverse(route.subList(i, j + 1));
                    }
            }
            return new Ordered(route.stream().mapToInt(Integer::intValue).toArray(), pathCost(v, route));
        }

        private double pathCost(int v, List<Integer> route) {
            double c = p.startDist[v][route.get(0)];
            for (int i = 1; i < route.size(); i++) c += p.stopDist[route.get(i - 1)][route.get(i)];
            return c;
        }

        private OptimizationResult buildResult(State best) {
            Map<String, VehicleRoute> routes = new LinkedHashMap<>();
            double total = 0;
            for (int v = 0; v < p.m; v++) {
                TreeSet<Integer> stopSet = new TreeSet<>();
                for (int i = 0; i < p.n; i++) if (best.assign[i] == v) stopSet.add(p.stopOf[i]);
                if (stopSet.isEmpty()) continue;
                int[] order = orderStops(v, stopSet.stream().mapToInt(Integer::intValue).toArray()).order();

                List<RouteLeg> legs = new ArrayList<>();
                double vehicleTotal = 0;
                int previous = -1;
                for (int stop : order) {
                    double leg = previous < 0 ? p.startDist[v][stop] : p.stopDist[previous][stop];
                    // Orders at the same drop point: HIGH first, then by id; only the first carries the leg.
                    List<Integer> here = new ArrayList<>();
                    for (int i = 0; i < p.n; i++) if (best.assign[i] == v && p.stopOf[i] == stop) here.add(i);
                    here.sort(Comparator.comparingInt((Integer i) -> p.rank[i])
                            .thenComparing(i -> p.orders[i].getOrderId()));
                    for (int idx = 0; idx < here.size(); idx++) {
                        double d = idx == 0 ? leg : 0.0;
                        legs.add(new RouteLeg(p.orders[here.get(idx)].getOrderId(), d));
                        vehicleTotal += d;
                    }
                    previous = stop;
                }
                routes.put(p.vehicles[v].getVehicleId(),
                        new VehicleRoute(p.vehicles[v].getVehicleId(), List.copyOf(legs), vehicleTotal));
                total += vehicleTotal;
            }
            return new OptimizationResult(Collections.unmodifiableMap(routes),
                    List.of(best.counts[0], best.counts[1], best.counts[2]), total);
        }
    }
}
