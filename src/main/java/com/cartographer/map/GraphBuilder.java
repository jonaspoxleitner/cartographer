package com.cartographer.map;

import com.cartographer.model.Node;
import com.cartographer.model.Route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges routes into a {@link RouteGraph}. Each intersection becomes
 * a single junction vertex shared by all routes passing through it.
 * Routes are cut at their junctions, and the stretches in between
 * become edges; a stretch traveled by several routes in either
 * direction is merged into one shared edge.
 */
public class GraphBuilder {

    // GPS jitter budget: two tracks are the same street (or the same place)
    // when they stay within this many meters of each other. Covers consumer
    // GPS inaccuracy of ~10 m per side, plus cut-point drift.
    private static final double GPS_OFFSET_FACTOR = 4.0;

    private final double toleranceMeters;

    public GraphBuilder(double toleranceMeters) {
        this.toleranceMeters = toleranceMeters;
    }

    /**
     * A point on a route's track where the graph is cut: a fractional
     * position (track index + fraction toward the next point) and the
     * vertex it belongs to. Fractional cuts mark junction projections — a
     * sparse route can pass a junction without having a track point there.
     */
    private record Cut(double position, GraphNode vertex) {}

    public RouteGraph build(List<Route> routes, List<Intersection> intersections) {
        RouteGraph graph = new RouteGraph();
        IdentityHashMap<Node, GraphNode> vertexByTrackNode = new IdentityHashMap<>();

        for (Intersection intersection : intersections) {
            GraphNode junction = graph.addJunction(intersection.getLocation());
            for (RoutePoint point : intersection.getPoints()) {
                vertexByTrackNode.put(point.node(), junction);
            }
        }

        for (Route route : routes) {
            List<Node> track = route.getNodes();
            if (track.size() < 2) {
                continue;
            }
            List<Cut> cuts = findCuts(track, vertexByTrackNode, graph);
            snapAtJunctions(track, cuts, graph.getJunctions());
            for (int c = 1; c < cuts.size(); c++) {
                addSegment(graph, route, track, cuts.get(c - 1), cuts.get(c));
            }
        }
        return graph;
    }

    /** Positions where a route passes a vertex: junctions plus start and end. */
    private List<Cut> findCuts(List<Node> track, IdentityHashMap<Node, GraphNode> vertexByTrackNode,
                               RouteGraph graph) {
        List<Cut> cuts = new ArrayList<>();
        for (int i = 0; i < track.size(); i++) {
            GraphNode vertex = vertexByTrackNode.get(track.get(i));
            if (vertex != null) {
                cuts.add(new Cut(i, vertex));
            }
        }
        cuts.add(0, new Cut(0, vertexByTrackNode.computeIfAbsent(track.get(0), graph::addEndpoint)));
        cuts.add(new Cut(track.size() - 1,
                vertexByTrackNode.computeIfAbsent(track.get(track.size() - 1), graph::addEndpoint)));
        return cuts;
    }

    /**
     * Sparse routes may pass a junction region without having a track point
     * inside it. If a junction lies within the GPS offset of one of the
     * route's segments, cut there too: the junction gets a projected point
     * on that segment, so all routes through the junction get edges that
     * start and end at the same vertex and merge cleanly.
     */
    private void snapAtJunctions(List<Node> track, List<Cut> cuts, List<GraphNode> junctions) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (GraphNode junction : junctions) {
                if (cutAt(cuts, junction) != null) {
                    continue;
                }
                Cut candidate = closestSegmentCut(track, cuts, junction);
                if (candidate != null) {
                    cuts.add(candidate);
                    cuts.sort(java.util.Comparator.comparingDouble(Cut::position));
                    changed = true;
                    break;
                }
            }
        }
    }

    private Cut cutAt(List<Cut> cuts, GraphNode vertex) {
        return cuts.stream().filter(c -> c.vertex() == vertex).findFirst().orElse(null);
    }

    /**
     * The best cut for a junction: the projection onto the closest track
     * segment, if within the GPS offset and not duplicating a nearby cut.
     */
    private Cut closestSegmentCut(List<Node> track, List<Cut> cuts, GraphNode junction) {
        Node p = junction.getLocation();
        double bestDistance = gpsOffsetMeters();
        Cut best = null;
        for (int k = 0; k < track.size() - 1; k++) {
            double[] t = new double[1];
            double distance = pointToSegmentMeters(p, track.get(k), track.get(k + 1), t);
            if (distance >= bestDistance) {
                continue;
            }
            double position = k + t[0];
            if (tooCloseToExistingCut(track, cuts, position)) {
                continue;
            }
            bestDistance = distance;
            best = new Cut(position, junction);
        }
        return best;
    }

    /** Avoid cutting the same stretch twice when two junctions' projections
     *  land within tolerance of each other on this route. */
    private boolean tooCloseToExistingCut(List<Node> track, List<Cut> cuts, double position) {
        Node candidate = pointAt(track, position);
        for (Cut cut : cuts) {
            if (Math.abs(cut.position() - position) <= 1.0
                    && candidate.distanceTo(pointAt(track, cut.position())) < toleranceMeters) {
                return true;
            }
        }
        return false;
    }

    /** Track point at a fractional position (linear interpolation). */
    private Node pointAt(List<Node> track, double position) {
        int base = (int) Math.floor(position);
        if (base >= track.size() - 1) {
            return track.get(track.size() - 1);
        }
        double t = position - base;
        if (t < 1e-9) {
            return track.get(base);
        }
        Node a = track.get(base);
        Node b = track.get(base + 1);
        return new Node(a.getLatitude() + t * (b.getLatitude() - a.getLatitude()),
                a.getLongitude() + t * (b.getLongitude() - a.getLongitude()));
    }

    /** Distance in meters from p to segment a-b; t[0] receives the projection parameter. */
    private double pointToSegmentMeters(Node p, Node a, Node b, double[] t) {
        double cosLat = Math.cos(Math.toRadians(p.getLatitude()));
        double mPerDegLat = 111_320.0;
        double ax = 0, ay = 0;
        double bx = (b.getLongitude() - a.getLongitude()) * cosLat * mPerDegLat;
        double by = (b.getLatitude() - a.getLatitude()) * mPerDegLat;
        double px = (p.getLongitude() - a.getLongitude()) * cosLat * mPerDegLat;
        double py = (p.getLatitude() - a.getLatitude()) * mPerDegLat;
        double len2 = bx * bx + by * by;
        double frac = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / len2));
        t[0] = frac;
        double dx = px - ax - frac * bx;
        double dy = py - ay - frac * by;
        return Math.hypot(dx, dy);
    }

    private void addSegment(RouteGraph graph, Route route, List<Node> track, Cut from, Cut to) {
        GraphNode fromVertex = from.vertex();
        GraphNode toVertex = to.vertex();

        List<Node> path = new ArrayList<>();
        path.add(pointAt(track, from.position()));
        for (int i = (int) Math.floor(from.position()) + 1; i <= (int) Math.ceil(to.position()) - 1; i++) {
            path.add(track.get(i));
        }
        path.add(pointAt(track, to.position()));

        if (fromVertex == toVertex && pathLength(path) < gpsOffsetMeters()) {
            return;
        }

        for (GraphEdge edge : graph.getEdges()) {
            if (sameEndpoints(edge, fromVertex, toVertex) && sameGeometry(edge.getPath(), path)) {
                edge.addRoute(route);
                return;
            }
        }
        graph.addEdge(new GraphEdge(fromVertex, toVertex, path, route));
    }

    private boolean sameEndpoints(GraphEdge edge, GraphNode from, GraphNode to) {
        return (edge.getFrom() == from && edge.getTo() == to)
                || (edge.getFrom() == to && edge.getTo() == from);
    }

    /**
     * Two paths describe the same stretch if every point on either path
     * stays close to the other (symmetric check — sampling only the shorter
     * path would only test a sparse chord's endpoints). Routes may traverse
     * the stretch in opposite directions.
     */
    private boolean sameGeometry(List<Node> a, List<Node> b) {
        double limit = 2 * toleranceMeters;
        return maxDistanceToPath(a, b) <= limit && maxDistanceToPath(b, a) <= limit;
    }

    private double maxDistanceToPath(List<Node> from, List<Node> to) {
        double max = 0;
        for (Node node : from) {
            max = Math.max(max, minDistanceTo(node, to));
        }
        return max;
    }

    private double minDistanceTo(Node sample, List<Node> path) {
        double min = Double.MAX_VALUE;
        for (Node node : path) {
            min = Math.min(min, sample.distanceTo(node));
        }
        return min;
    }

    /**
     * Simplifies a built graph. Two adjacent track points within tolerance
     * of each other become separate junctions even though they are the same
     * street, so chains of junctions linked only by shared edges are
     * contracted into single edges between the street's real ends. Endpoints
     * sitting right next to a junction (same place, GPS jitter, or a loop's
     * start/finish) are fused into it. Applied repeatedly until stable.
     */
    public RouteGraph simplify(RouteGraph graph) {
        // Least destructive first: contraction preserves the path geometry;
        // junction fusion drops edges shorter than the GPS offset, so it
        // runs last. Otherwise it can cascade along a street of closely
        // spaced corridor junctions and swallow the street's length.
        RouteGraph current = graph;
        while (true) {
            RouteGraph next = fuseNearbyEndpoints(current);
            if (next == null) {
                next = contractSharedChains(current);
            }
            if (next == null) {
                next = mergeParallelEdges(current);
            }
            if (next == null) {
                next = fuseCloseJunctions(current);
            }
            if (next == null) {
                return current;
            }
            current = next;
        }
    }

    /**
     * Two junctions connected by an edge shorter than the GPS offset are the
     * same place (e.g. several routes starting within meters of each other
     * at a trailhead). Fuse them into one, keeping the better-connected one.
     */
    private RouteGraph fuseCloseJunctions(RouteGraph graph) {
        for (GraphEdge edge : graph.getEdges()) {
            GraphNode from = edge.getFrom();
            GraphNode to = edge.getTo();
            if (from == to || !from.isJunction() || !to.isJunction()
                    || edge.getLengthMeters() >= gpsOffsetMeters()) {
                continue;
            }
            // Both ends in the middle of a doubly-traveled street are a
            // corridor (contraction's job), not one place; never fuse those.
            if (isCorridorJunction(graph, from) && isCorridorJunction(graph, to)) {
                continue;
            }
            GraphNode keep = graph.edgesFrom(from).size() >= graph.edgesFrom(to).size()
                    ? from : to;
            GraphNode drop = keep == from ? to : from;
            return rebuild(graph, Map.of(drop, keep), Set.of(), Set.of(), List.of());
        }
        return null;
    }

    /** Degree 2 with shared edges on both sides: a waypoint along a street. */
    private boolean isCorridorJunction(RouteGraph graph, GraphNode junction) {
        List<GraphEdge> edges = graph.edgesFrom(junction);
        return edges.size() == 2 && edges.get(0).isShared() && edges.get(1).isShared();
    }

    /** The constant offset we accept as GPS noise. */
    private double gpsOffsetMeters() {
        return GPS_OFFSET_FACTOR * toleranceMeters;
    }

    /**
     * Two edges connecting the same vertices with (nearly) the same length
     * are the same street, even if their shapes differ: a spare track with
     * no points between junctions becomes a straight chord while the
     * detailed track follows the road's curve (chord-vs-arc error, far
     * beyond GPS jitter in lateral distance but not in length). Merge them
     * so the corridor can contract. Genuinely different paths between the
     * same vertices (e.g. a loop vs. a short street) have very different
     * lengths and stay separate.
     */
    private RouteGraph mergeParallelEdges(RouteGraph graph) {
        double limit = gpsOffsetMeters();
        List<GraphEdge> edges = graph.getEdges();
        for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
                GraphEdge e1 = edges.get(i);
                GraphEdge e2 = edges.get(j);
                if (e1.getFrom() == e1.getTo() || e2.getFrom() == e2.getTo()) {
                    continue; // self-loops follow their own path
                }
                boolean samePair = (e1.getFrom() == e2.getFrom() && e1.getTo() == e2.getTo())
                        || (e1.getFrom() == e2.getTo() && e1.getTo() == e2.getFrom());
                boolean similarLength = Math.abs(e1.getLengthMeters() - e2.getLengthMeters()) <= limit
                        && e1.getLengthMeters() / Math.max(e2.getLengthMeters(), 1) >= 0.7;
                if (!samePair || !similarLength) {
                    continue;
                }
                GraphEdge keep = e1.isShared() ? e1 : e2;
                Set<Route> routes = new LinkedHashSet<>();
                routes.addAll(e1.getRoutes());
                routes.addAll(e2.getRoutes());
                return rebuild(graph, Map.of(), Set.of(), Set.of(e1, e2),
                        List.of(newEdge(keep.getFrom(), keep.getTo(), keep.getPath(), routes,
                                e1.getTraversals() + e2.getTraversals())));
            }
        }
        return null;
    }

    /** Fold an endpoint within reach of a junction into that junction. */
    private RouteGraph fuseNearbyEndpoints(RouteGraph graph) {
        double radius = gpsOffsetMeters();
        for (GraphNode endpoint : graph.getNodes()) {
            if (endpoint.isJunction()) {
                continue;
            }
            GraphNode nearest = null;
            double best = radius;
            for (GraphNode junction : graph.getJunctions()) {
                double distance = endpoint.getLocation().distanceTo(junction.getLocation());
                if (distance < best) {
                    best = distance;
                    nearest = junction;
                }
            }
            if (nearest != null) {
                return rebuild(graph, Map.of(endpoint, nearest),
                        Set.of(), Set.of(), List.of());
            }
        }
        return null;
    }

    /** A junction with exactly two shared edges (and the same routes on both
     *  sides) is a waypoint along a street, not a junction: merge the edges. */
    private RouteGraph contractSharedChains(RouteGraph graph) {
        for (GraphNode junction : graph.getJunctions()) {
            List<GraphEdge> edges = graph.edgesFrom(junction);
            if (edges.size() != 2) {
                continue;
            }
            GraphEdge e1 = edges.get(0);
            GraphEdge e2 = edges.get(1);
            if (!e1.isShared() || !e2.isShared()
                    || e1.getFrom() == e1.getTo() || e2.getFrom() == e2.getTo()
                    || Collections.disjoint(e1.getRoutes(), e2.getRoutes())) {
                continue;
            }
            GraphNode v1 = e1.otherEnd(junction);
            GraphNode v2 = e2.otherEnd(junction);
            if (v1 == v2) {
                continue;
            }
            List<Node> path = concat(pathInto(e1, junction), pathOutOf(e2, junction));
            Set<Route> routes = new LinkedHashSet<>();
            routes.addAll(e1.getRoutes());
            routes.addAll(e2.getRoutes());
            return rebuild(graph, Map.of(), Set.of(junction), Set.of(e1, e2),
                    List.of(newEdge(v1, v2, path, routes,
                            Math.max(e1.getTraversals(), e2.getTraversals()))));
        }
        return null;
    }

    /** Edge path oriented so it ends at {@code junction}. */
    private List<Node> pathInto(GraphEdge edge, GraphNode junction) {
        return reversed(pathOutOf(edge, junction));
    }

    /** Edge path oriented so it starts at {@code junction}. */
    private List<Node> pathOutOf(GraphEdge edge, GraphNode junction) {
        return edge.getFrom() == junction ? edge.getPath() : reversed(edge.getPath());
    }

    private List<Node> concat(List<Node> first, List<Node> second) {
        List<Node> combined = new ArrayList<>(first);
        combined.addAll(second.subList(1, second.size()));
        return combined;
    }

    private List<Node> reversed(List<Node> path) {
        List<Node> result = new ArrayList<>(path);
        Collections.reverse(result);
        return result;
    }

    private GraphEdge newEdge(GraphNode from, GraphNode to, List<Node> path, Set<Route> routes,
                              int traversals) {
        GraphEdge edge = new GraphEdge(from, to, path, routes.iterator().next());
        routes.stream().skip(1).forEach(edge::addRoute);
        edge.setTraversals(traversals);
        return edge;
    }

    private RouteGraph rebuild(RouteGraph graph, Map<GraphNode, GraphNode> remap,
                               Set<GraphNode> removedVertices, Set<GraphEdge> removedEdges,
                               List<GraphEdge> extraEdges) {
        RouteGraph result = new RouteGraph();
        for (GraphNode node : graph.getNodes()) {
            if (!remap.containsKey(node) && !removedVertices.contains(node)) {
                result.addExisting(node);
            }
        }
        for (GraphEdge edge : graph.getEdges()) {
            if (removedEdges.contains(edge)) {
                continue;
            }
            GraphNode from = remap.getOrDefault(edge.getFrom(), edge.getFrom());
            GraphNode to = remap.getOrDefault(edge.getTo(), edge.getTo());
            if (from == to && edge.getLengthMeters() < gpsOffsetMeters()) {
                // stub folded into the junction, shorter than GPS noise
                continue;
            }
            result.addEdge(newEdge(from, to, edge.getPath(), edge.getRoutes(), edge.getTraversals()));
        }
        extraEdges.forEach(result::addEdge);
        return result;
    }

    private double pathLength(List<Node> path) {
        double length = 0;
        for (int i = 1; i < path.size(); i++) {
            length += path.get(i - 1).distanceTo(path.get(i));
        }
        return length;
    }
}
