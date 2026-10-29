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

    // Same-route points must be this many track points apart to count as a
    // self-intersection; closer ones are just neighboring track points.
    private static final int MIN_SELF_INTERSECTION_GAP = 8;

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
            if (qualifies(cluster, includeSelf)) {
                intersections.add(new Intersection(centroid(cluster), cluster));
            }
        }
        return intersections;
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
                                        || (includeSelf
                                        && Math.abs(a.index() - b.index()) >= MIN_SELF_INTERSECTION_GAP)) {
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

    /**
     * A cluster is an intersection if it spans several routes, or (when
     * enabled) holds far-apart points of one route: the same road walked
     * twice, or a loop crossing itself. Neighboring same-direction track
     * points never qualify on their own.
     */
    private boolean qualifies(List<RoutePoint> cluster, boolean includeSelf) {
        for (int i = 0; i < cluster.size(); i++) {
            for (int j = i + 1; j < cluster.size(); j++) {
                RoutePoint a = cluster.get(i);
                RoutePoint b = cluster.get(j);
                if (a.route() != b.route()
                        || (includeSelf
                            && Math.abs(a.index() - b.index()) >= MIN_SELF_INTERSECTION_GAP)) {
                    return true;
                }
            }
        }
        return false;
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
