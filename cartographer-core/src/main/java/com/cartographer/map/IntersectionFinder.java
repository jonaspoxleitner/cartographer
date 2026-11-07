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
    // swallow the tracks passing through it. The one exception is a
    // corridor walked twice (see splitCorridor).
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
                intersections.addAll(splitCorridor(cluster));
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
     * A cluster too spread out to be a junction may be a corridor walked
     * twice: the passes stay within tolerance along the whole length, so they
     * chain into one cluster that is street-length long instead of
     * point-sized. Cut it at the corridor's two ends instead of dropping it:
     * the graph then merges the passes into one shared street.
     * <p>
     * The passes are one route retracing its steps, or two routes that
     * recorded the same street. Two runs are a corridor when their ends meet
     * at both ends, either running in opposite directions (classic out and
     * back: one run's start meets the other's end) or the same direction (the
     * street walked twice the same way: starts meet starts). Three runs are a
     * corridor when one route walked the street once while the other walked it
     * once in each direction: only the route that walked it once contributes a
     * single run, and that run is the shared stretch. Either way every route in
     * the cluster has to reach both corridor ends, and no route may contribute
     * more than two runs. Everything else is a coverage pattern with its own
     * crossings, not one corridor: a route walking the street three times,
     * three routes walking it once each, and a cluster whose ends are not
     * pinned to the two places the corridor runs between.
     * <p>
     * Package-private so the corridor rule can be tested on a cluster whose
     * routes do not all reach both ends: the clustering itself cannot produce
     * one, because every point it clusters lies within tolerance of the other
     * route and the reach tolerance is wider than that.
     */
    List<Intersection> splitCorridor(List<RoutePoint> cluster) {
        List<Run> runs = corridorRuns(cluster);
        Run sharedStretch = sharedStretch(runs);
        if (sharedStretch == null) {
            return List.of();
        }
        List<RoutePoint> ends = corridorEnds(cluster, sharedStretch);
        if (ends == null) {
            return List.of();
        }
        Intersection start = corridorEnd(cluster, runs, ends.get(0));
        Intersection end = corridorEnd(cluster, runs, ends.get(1));
        return start != null && end != null ? List.of(start, end) : List.of();
    }

    /**
     * The run whose two ends are the corridor's ends, or null when the
     * cluster's runs do not pair into one corridor. Two runs are the two
     * passes over the street, so either one spans the shared stretch. Three
     * runs are one single pass of one route plus an out-and-back pass of
     * another: only the route that walked the street once contributes exactly
     * one run, and that run is the shared stretch.
     */
    private Run sharedStretch(List<Run> runs) {
        if (runs.size() == 2) {
            return runs.get(0);
        }
        if (runs.size() != 3) {
            return null;
        }
        List<Run> single = runs.stream()
                .filter(run -> runsOf(runs, run.first().route()) == 1)
                .toList();
        return single.size() == 1 ? single.get(0) : null;
    }

    private long runsOf(List<Run> runs, Route route) {
        return runs.stream().filter(run -> run.first().route() == route).count();
    }

    /**
     * The corridor's two ends: the ends of the shared stretch. A stretch that
     * ends where it starts was walked out and back inside a single run, so its
     * far end is that run's turnaround. Null when it turns around where it
     * started: then the cluster holds no stretch to cut.
     */
    private List<RoutePoint> corridorEnds(List<RoutePoint> cluster, Run sharedStretch) {
        RoutePoint start = sharedStretch.first();
        if (start.node().distanceTo(sharedStretch.last().node()) > corridorProximityMeters()) {
            return List.of(start, sharedStretch.last());
        }
        RoutePoint turnaround = turnaround(cluster, sharedStretch);
        if (turnaround.node().distanceTo(start.node()) <= corridorProximityMeters()) {
            return null;
        }
        return List.of(start, turnaround);
    }

    /** The point of the run farthest from its start: where the run turned
     *  around, once it walked out and came back the same way. */
    private RoutePoint turnaround(List<RoutePoint> cluster, Run run) {
        RoutePoint start = run.first();
        RoutePoint farthest = start;
        double farthestDistance = 0;
        for (RoutePoint point : runPoints(cluster, run)) {
            double distance = point.node().distanceTo(start.node());
            if (distance > farthestDistance) {
                farthestDistance = distance;
                farthest = point;
            }
        }
        return farthest;
    }

    /** The route's cluster points inside the run, in track order. */
    private List<RoutePoint> runPoints(List<RoutePoint> cluster, Run run) {
        return cluster.stream()
                .filter(point -> point.route() == run.first().route())
                .filter(point -> point.index() >= run.first().index()
                        && point.index() <= run.last().index())
                .sorted(Comparator.comparingInt(RoutePoint::index))
                .toList();
    }

    /** A pass over the corridor: its first and last clustered track point. */
    private record Run(RoutePoint first, RoutePoint last) {}

    /**
     * The cluster's passes, split per route: a track index only means
     * something within its own route's track, so each route's cluster points
     * are ordered and cut into runs on their own. A street recorded by two
     * routes holds one pass of each; a route that walked the corridor twice
     * contributes two runs.
     */
    private List<Run> corridorRuns(List<RoutePoint> cluster) {
        List<Run> runs = new ArrayList<>();
        for (Route route : distinctRoutes(cluster)) {
            List<RoutePoint> ordered = cluster.stream()
                    .filter(point -> point.route() == route)
                    .sorted(Comparator.comparingInt(RoutePoint::index))
                    .toList();
            runs.addAll(splitRuns(ordered));
        }
        return runs;
    }

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
     * One corridor end, cut once per visit: every stretch of a route that comes
     * within the corridor's width of the end contributes one point. A route that
     * reaches the same end twice — at the two ends of one out-and-back run, or
     * with one pass per direction in two runs — is thus cut there twice, and
     * neither pass stays a separate parallel edge. Null when a route in the
     * cluster does not reach the end at all: then the ends are not pinned to two
     * places and the cluster is a coverage pattern.
     */
    private Intersection corridorEnd(List<RoutePoint> cluster, List<Run> runs, RoutePoint corridorEnd) {
        List<RoutePoint> visits = new ArrayList<>(runs.size());
        for (Route route : distinctRoutes(cluster)) {
            int before = visits.size();
            for (Run run : runs) {
                if (run.first().route() == route) {
                    addVisits(visits, run, runPoints(cluster, run), corridorEnd);
                }
            }
            if (visits.size() == before) {
                return null;
            }
        }
        return new Intersection(centroid(visits), visits);
    }

    /** Adds one cut per contiguous stretch of run points within the corridor's
     *  width of the end. */
    private void addVisits(List<RoutePoint> visits, Run run, List<RoutePoint> runPoints,
                           RoutePoint corridorEnd) {
        List<RoutePoint> visit = new ArrayList<>();
        for (RoutePoint point : runPoints) {
            if (point.node().distanceTo(corridorEnd.node()) > corridorProximityMeters()) {
                if (!visit.isEmpty()) {
                    visits.add(visitedPoint(visit, run, corridorEnd));
                    visit.clear();
                }
            } else {
                visit.add(point);
            }
        }
        if (!visit.isEmpty()) {
            visits.add(visitedPoint(visit, run, corridorEnd));
        }
    }

    /**
     * Where one visit is cut. A visit that includes one of the run's own ends
     * is cut there: the corridor end is a run end, so the cut stays on the
     * recorded point that delimits the pass. A visit the run passes through in
     * the middle of its track has no such point and is cut at the recording
     * closest to the corridor end instead.
     */
    private RoutePoint visitedPoint(List<RoutePoint> visit, Run run, RoutePoint corridorEnd) {
        List<RoutePoint> runEnds = visit.stream()
                .filter(point -> point.index() == run.first().index()
                        || point.index() == run.last().index())
                .toList();
        return nearest(runEnds.isEmpty() ? visit : runEnds, corridorEnd);
    }

    private RoutePoint nearest(List<RoutePoint> points, RoutePoint corridorEnd) {
        RoutePoint nearest = points.get(0);
        for (RoutePoint point : points) {
            if (point.node().distanceTo(corridorEnd.node())
                    < nearest.node().distanceTo(corridorEnd.node())) {
                nearest = point;
            }
        }
        return nearest;
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
