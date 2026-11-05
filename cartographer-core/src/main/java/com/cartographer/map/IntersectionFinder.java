package com.cartographer.map;

import com.cartographer.model.Node;
import com.cartographer.model.Route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Finds intersections between routes by clustering track nodes
 * that lie within a tolerance of each other. Clustering uses
 * union-find, so nearby nodes chain into one intersection even
 * when no single pair spans it.
 * <p>
 * Candidate pairs come from a spatial grid (only points in the 3x3
 * cells around a point can be close enough), and the candidate
 * search runs in parallel, one track point per task. Union-find
 * merging then applies the sorted pairs in a deterministic order,
 * so results are identical to the sequential version.
 */
public class IntersectionFinder {

    // Two track points of the same route are a self-intersection only if
    // the route really traveled elsewhere between them: the track midpoint
    // between the two points must be at least this far (in tolerance
    // multiples) away. An index- or arc-length-based gap is meaningless
    // for dense time-based GPX recordings (a point every second): standing
    // still or walking slowly chains the whole track into one cluster.
    private static final double MIN_SELF_INTERSECTION_DETOUR_FACTOR = 4.0;

    // A junction is a point-sized place. A cluster spread wider than this
    // (in tolerance multiples) is a region of overlapping dense tracks —
    // e.g. coverage walking with parallel passes within the tolerance —
    // not a crossroads. Cutting a track at such a cluster's centroid would
    // swallow the tracks passing through it. The one exception is an
    // out-and-back corridor (see splitOutAndBack).
    private static final double MAX_CLUSTER_EXTENT_FACTOR = 4.0;

    // Track points sampled at most this many indices apart belong to the
    // same pass over a corridor. A longer gap can be a turnaround, but a
    // standstill or GPS dropout on the street leaves one pass intact.
    private static final int MAX_RUN_INDEX_GAP = 5;

    // A corridor's two legs stay within this many tolerance multiples of
    // each other: their ends meet within it, and a track that keeps every
    // skipped point within it never left the corridor.
    private static final double CORRIDOR_PROXIMITY_FACTOR = 2.0;

    private static final double METERS_PER_DEGREE_LATITUDE = 111_000;

    private final double toleranceMeters;

    public IntersectionFinder(double toleranceMeters) {
        if (toleranceMeters <= 0) {
            throw new IllegalArgumentException("Tolerance must be positive");
        }
        this.toleranceMeters = toleranceMeters;
    }

    /** Cross-route intersections only; a route meeting itself is ignored. */
    public List<Intersection> find(List<Route> routes) {
        return find(routes, false);
    }

    /** Also treats a route crossing or retracing its own path (out and
     *  back) as intersections: those are real crossroads on the map. */
    public List<Intersection> findWithSelfIntersections(List<Route> routes) {
        return find(routes, true);
    }

    private List<Intersection> find(List<Route> routes, boolean includeSelf) {
        List<RoutePoint> allPoints = flatten(routes);
        Map<CellKey, List<Integer>> grid = buildGrid(allPoints);

        // parallel search, deterministic merge
        List<int[]> closePairs = findClosePairs(allPoints, grid, includeSelf);
        UnionFind uf = new UnionFind(allPoints.size());
        for (int[] pair : closePairs) {
            uf.union(pair[0], pair[1]);
        }

        Map<Integer, List<RoutePoint>> clusters = new HashMap<>();
        for (int i = 0; i < allPoints.size(); i++) {
            clusters.computeIfAbsent(uf.find(i), k -> new ArrayList<>()).add(allPoints.get(i));
        }

        List<Intersection> intersections = new ArrayList<>();
        for (List<RoutePoint> cluster : clusters.values()) {
            if (clusterIsTight(cluster)) {
                if (qualifies(cluster, includeSelf)) {
                    intersections.add(new Intersection(centroid(cluster), cluster));
                }
            } else {
                intersections.addAll(splitOutAndBack(cluster));
            }
        }
        return intersections;
    }

    /**
     * Between the two close points the track goes somewhere and comes back:
     * its midpoint is far from where the points meet. True for retracing a
     * street or a loop, false for lingering in place or slow dense sampling
     * (GPS jitter never accumulates a detour).
     */
    private boolean isAnotherPass(RoutePoint a, RoutePoint b) {
        List<Node> track = a.route().getNodes();
        int mid = (a.index() + b.index()) / 2;
        return a.node().distanceTo(track.get(mid)) >= minSelfIntersectionDetourMeters();
    }

    /**
     * A same-route cluster that is too spread out to be a junction may be a
     * corridor walked twice: the street and its return leg stay within
     * tolerance along the whole length, so both legs chain into one cluster
     * that is street-length long instead of point-sized. Cut it at the
     * corridor's two ends instead of dropping it: the graph then merges the
     * two legs into one shared street.
     * <p>
     * The legs are the cluster's contiguous index runs. They meet at the two
     * corridor ends, either running in opposite directions (classic out and
     * back: one run's start meets the other's end) or the same direction
     * (the street walked twice the same way: starts meet starts). Exactly two
     * runs that meet at both ends are a corridor; more runs are a coverage
     * pattern with its own crossings, not a single corridor.
     */
    private List<Intersection> splitOutAndBack(List<RoutePoint> cluster) {
        if (distinctRoutes(cluster).size() != 1) {
            return List.of(); // cross-route clusters are never one corridor
        }
        List<RoutePoint> ordered = new ArrayList<>(cluster);
        ordered.sort(Comparator.comparingInt(RoutePoint::index));
        List<Run> runs = splitRuns(ordered);
        if (runs.size() != 2) {
            return List.of();
        }
        Run first = runs.get(0);
        Run second = runs.get(1);
        List<Intersection> opposite = corridorEnds(first.first(), second.last(),
                first.last(), second.first());
        return opposite != null ? opposite
                : corridorEnds(first.first(), second.first(), first.last(), second.last());
    }

    /** A pass over the corridor: its first and last clustered track point. */
    private record Run(RoutePoint first, RoutePoint last) {}

    private List<Run> splitRuns(List<RoutePoint> ordered) {
        List<Run> runs = new ArrayList<>();
        RoutePoint first = ordered.get(0);
        RoutePoint previous = first;
        for (RoutePoint point : ordered.subList(1, ordered.size())) {
            if (isRunBoundary(previous, point)) {
                runs.add(new Run(first, previous));
                first = point;
            }
            previous = point;
        }
        runs.add(new Run(first, previous));
        return runs;
    }

    /**
     * A gap in cluster membership splits a pass only when the track really
     * left the corridor in between: standing still on the street (or a GPS
     * dropout) keeps every skipped point within reach of both run ends, a
     * turnaround walks away from them.
     */
    private boolean isRunBoundary(RoutePoint before, RoutePoint after) {
        if (after.index() - before.index() <= MAX_RUN_INDEX_GAP) {
            return false;
        }
        List<Node> track = before.route().getNodes();
        double reach = corridorProximityMeters();
        for (int i = before.index() + 1; i < after.index(); i++) {
            Node skipped = track.get(i);
            if (skipped.distanceTo(before.node()) > reach
                    && skipped.distanceTo(after.node()) > reach) {
                return true;
            }
        }
        return false;
    }

    /** How close two passes of a corridor stay to each other. */
    private double corridorProximityMeters() {
        return CORRIDOR_PROXIMITY_FACTOR * toleranceMeters;
    }

    /**
     * The two corridor ends, each pairing one run end with its partner, or
     * null if the ends do not meet: a corridor's legs share both ends.
     */
    private List<Intersection> corridorEnds(RoutePoint startA, RoutePoint startB,
                                            RoutePoint endA, RoutePoint endB) {
        if (!meetAtCorridorEnd(startA, startB) || !meetAtCorridorEnd(endA, endB)) {
            return null;
        }
        return List.of(
                new Intersection(centroid(List.of(startA, startB)), List.of(startA, startB)),
                new Intersection(centroid(List.of(endA, endB)), List.of(endA, endB)));
    }

    private boolean meetAtCorridorEnd(RoutePoint a, RoutePoint b) {
        return a.node().distanceTo(b.node()) <= corridorProximityMeters();
    }

    /**
     * The cluster's bounding box fits within the maximum extent on both
     * axes. Chained pairs let a cluster creep arbitrarily far (each new
     * point only needs to be close to the last one), so chaining alone
     * cannot keep clusters point-sized.
     */
    private boolean clusterIsTight(List<RoutePoint> cluster) {
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (RoutePoint point : cluster) {
            Node node = point.node();
            minLat = Math.min(minLat, node.getLatitude());
            maxLat = Math.max(maxLat, node.getLatitude());
            minLon = Math.min(minLon, node.getLongitude());
            maxLon = Math.max(maxLon, node.getLongitude());
        }
        double latMeters = (maxLat - minLat) * METERS_PER_DEGREE_LATITUDE;
        double lonMeters = (maxLon - minLon) * METERS_PER_DEGREE_LATITUDE
                * Math.cos(Math.toRadians(cluster.get(0).node().getLatitude()));
        double limit = MAX_CLUSTER_EXTENT_FACTOR * toleranceMeters;
        return latMeters <= limit && lonMeters <= limit;
    }

    /**
     * All point pairs within tolerance, sorted lexicographically by index
     * so the union phase mirrors the old sequential scan order. Points
     * farther apart than one grid cell can never be within tolerance, so
     * only the 3x3 cells around each point are examined.
     */
    private List<int[]> findClosePairs(List<RoutePoint> allPoints,
                                       Map<CellKey, List<Integer>> grid,
                                       boolean includeSelf) {
        List<int[]> pairs = IntStream.range(0, allPoints.size()).parallel()
                .mapToObj(i -> {
                    RoutePoint a = allPoints.get(i);
                    CellKey cell = cellKey(a.node());
                    // a cell spans the tolerance north-south, but longitude
                    // degrees shrink with latitude, so a point within the
                    // tolerance can sit several cells away east-west
                    int lonReach = (int) Math.ceil(1.0 / Math.cos(Math.toRadians(a.node().getLatitude())));
                    List<int[]> found = new ArrayList<>();
                    for (int dlat = -1; dlat <= 1; dlat++) {
                        for (int dlon = -lonReach; dlon <= lonReach; dlon++) {
                            List<Integer> bucket = grid.get(new CellKey(cell.lat() + dlat, cell.lon() + dlon));
                            if (bucket == null) {
                                continue;
                            }
                            for (int j : bucket) {
                                if (j <= i) {
                                    continue; // each unordered pair once
                                }
                                RoutePoint b = allPoints.get(j);
                                if (a.node().distanceTo(b.node()) > toleranceMeters) {
                                    continue;
                                }
                                if (a.route() != b.route()
                                        || (includeSelf && isAnotherPass(a, b))) {
                                    found.add(new int[]{i, j});
                                }
                            }
                        }
                    }
                    return found;
                })
                .flatMap(List::stream)
                .collect(Collectors.toCollection(ArrayList::new));
        pairs.sort(Comparator.comparingInt((int[] p) -> p[0]).thenComparingInt(p -> p[1]));
        return pairs;
    }

    private List<Route> distinctRoutes(List<RoutePoint> cluster) {
        return cluster.stream().map(RoutePoint::route).distinct().toList();
    }

    /**
     * A cluster is an intersection if it spans several routes, or (when
     * enabled) holds two points of one route with a real detour between
     * them: the same road walked twice, or a loop crossing itself.
     * Neighboring same-direction track points never qualify on their own.
     */
    private boolean qualifies(List<RoutePoint> cluster, boolean includeSelf) {
        for (int i = 0; i < cluster.size(); i++) {
            for (int j = i + 1; j < cluster.size(); j++) {
                RoutePoint a = cluster.get(i);
                RoutePoint b = cluster.get(j);
                if (a.route() != b.route()
                        || (includeSelf && isAnotherPass(a, b))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Minimum distance of the track midpoint between two same-route points
     *  for them to count as another pass rather than lingering in place. */
    private double minSelfIntersectionDetourMeters() {
        return MIN_SELF_INTERSECTION_DETOUR_FACTOR * toleranceMeters;
    }

    private List<RoutePoint> flatten(List<Route> routes) {
        List<RoutePoint> points = new ArrayList<>();
        for (Route route : routes) {
            List<Node> nodes = route.getNodes();
            for (int i = 0; i < nodes.size(); i++) {
                points.add(new RoutePoint(route, i, nodes.get(i)));
            }
        }
        return points;
    }

    private Map<CellKey, List<Integer>> buildGrid(List<RoutePoint> allPoints) {
        Map<CellKey, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < allPoints.size(); i++) {
            grid.computeIfAbsent(cellKey(allPoints.get(i).node()), key -> new ArrayList<>()).add(i);
        }
        return grid;
    }

    private CellKey cellKey(Node node) {
        double cellDegrees = toleranceMeters / METERS_PER_DEGREE_LATITUDE;
        return new CellKey(
                (int) Math.floor(node.getLatitude() / cellDegrees),
                (int) Math.floor(node.getLongitude() / cellDegrees));
    }

    private Node centroid(List<RoutePoint> cluster) {
        double lat = 0;
        double lon = 0;
        for (RoutePoint point : cluster) {
            lat += point.node().getLatitude();
            lon += point.node().getLongitude();
        }
        return new Node(lat / cluster.size(), lon / cluster.size());
    }

    private record CellKey(int lat, int lon) {
    }

    private static class UnionFind {

        private final int[] parent;

        UnionFind(int size) {
            parent = new int[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
        }

        int find(int i) {
            while (parent[i] != i) {
                parent[i] = parent[parent[i]];
                i = parent[i];
            }
            return i;
        }

        void union(int a, int b) {
            parent[find(a)] = find(b);
        }
    }
}
