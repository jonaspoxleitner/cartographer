package com.cartographer.map;

import com.cartographer.model.Node;
import com.cartographer.model.Route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds intersections between routes by clustering track nodes
 * that lie within a tolerance of each other. Clustering uses
 * union-find, so nearby nodes chain into one intersection even
 * when no single pair spans it.
 */
public class IntersectionFinder {

    // Same-route points must be this many track points apart to count as a
    // self-intersection; closer ones are just neighboring track points.
    private static final int MIN_SELF_INTERSECTION_GAP = 8;

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

        UnionFind uf = new UnionFind(allPoints.size());
        for (int i = 0; i < allPoints.size(); i++) {
            for (int j = i + 1; j < allPoints.size(); j++) {
                RoutePoint a = allPoints.get(i);
                RoutePoint b = allPoints.get(j);
                if (a.node().distanceTo(b.node()) > toleranceMeters) {
                    continue;
                }
                if (a.route() != b.route()
                        || (includeSelf
                            && Math.abs(a.index() - b.index()) >= MIN_SELF_INTERSECTION_GAP)) {
                    uf.union(i, j);
                }
            }
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

    private Node centroid(List<RoutePoint> cluster) {
        double lat = 0;
        double lon = 0;
        for (RoutePoint point : cluster) {
            lat += point.node().getLatitude();
            lon += point.node().getLongitude();
        }
        return new Node(lat / cluster.size(), lon / cluster.size());
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
