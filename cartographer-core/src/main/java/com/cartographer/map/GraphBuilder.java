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

    // The simplification steps converge: each pass either removes a vertex
    // or splits an edge at a fork a full GPS offset away from every other
    // fork. If a step still returns a new graph after this many passes it
    // is spinning, and the exception names it instead of hanging silently.
    private static final int MAX_SIMPLIFY_PASSES = 200;

    // Distances come from a local flat projection, so two points at the same
    // place can differ by rounding noise. A re-terminated path's closest
    // approach must beat its stale end by more than that to be a pass-by.
    private static final double CLOSEST_APPROACH_EPSILON_METERS = 1e-6;

    // Edges end this far from the junction coordinate and continue as a
    // straight spoke to it, so every edge meeting at a junction terminates
    // at the identical vertex. Which track point a route happened to record
    // inside the junction cluster no longer leaks into the drawn geometry.
    // Public because it decides stored tile bytes: the server feeds it into
    // the fingerprint of its tile store, so changing it re-renders them.
    public static final double JUNCTION_CIRCLE_RADIUS_METERS = 5.0;

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
     * <p>
     * Cluster cuts are placed by {@link #findCuts} before this runs, and a
     * projected cut is only made where no cut exists yet, so on the route
     * that recorded the junction its own cluster cut always wins.
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
     * The cut for a junction: the projection onto the closest track segment,
     * if within the GPS offset. Only that one projection counts — if it lands
     * on an existing cut, the existing cut already is this junction's point on
     * the route, and {@link #fuseCloseJunctions} merges the two vertices, so no
     * further segment is tried. Falling back would cut the route at a spot the
     * junction does not touch, and the edge would then run past the junction.
     * <p>
     * A projection clamped to a segment end is not a pass either: it only says
     * the junction lies somewhere beyond that segment, up to the GPS offset
     * away. Cutting at the sample there would drag the junction off its place.
     */
    private Cut closestSegmentCut(List<Node> track, List<Cut> cuts, GraphNode junction) {
        Node p = junction.getLocation();
        double bestDistance = gpsOffsetMeters();
        Cut best = null;
        for (int k = 0; k < track.size() - 1; k++) {
            double[] t = new double[1];
            double distance = pointToSegmentMeters(p, track.get(k), track.get(k + 1), t);
            // a projection at a segment end was clamped: the junction lies
            // beyond that segment, not beside the route
            if (distance >= bestDistance || t[0] <= 0 || t[0] >= 1) {
                continue;
            }
            bestDistance = distance;
            best = new Cut(k + t[0], junction);
        }
        if (best != null && tooCloseToExistingCut(track, cuts, best.position())) {
            return null;
        }
        return best;
    }

    /**
     * Avoid cutting the same spot twice when two junctions' projections land
     * on the same place. Measured in metres along the track, not in track
     * indices: one index is a metre on a dense recording and 140 m on a sparse
     * one, and the tolerance decides whether two cuts are the same point.
     */
    private boolean tooCloseToExistingCut(List<Node> track, List<Cut> cuts, double position) {
        Node candidate = pointAt(track, position);
        for (Cut cut : cuts) {
            if (candidate.distanceTo(pointAt(track, cut.position())) < toleranceMeters) {
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

        path = cutAtJunctionCircles(path, fromVertex, toVertex);

        for (GraphEdge edge : graph.getEdges()) {
            if (sameEndpoints(edge, fromVertex, toVertex) && sameGeometry(edge.getPath(), path)) {
                edge.addRoute(route);
                return;
            }
        }
        graph.addEdge(new GraphEdge(fromVertex, toVertex, path, route));
    }

    /**
     * Terminates the segment at its junction ends deterministically: the path
     * is cut where it crosses the junction circle, then continues as a
     * straight spoke to the junction coordinate. Route starts and finishes
     * are not touched.
     */
    private List<Node> cutAtJunctionCircles(List<Node> path, GraphNode from, GraphNode to) {
        List<Node> cut = path;
        if (from.isJunction()) {
            cut = cutAtStart(cut, from.getLocation());
        }
        if (to.isJunction()) {
            cut = cutAtEnd(cut, to.getLocation());
        }
        return cut;
    }

    /**
     * Start end of a path, cut at the junction circle: the junction
     * coordinate, the circle crossing, then the course outside the circle.
     * A path that never leaves the circle has no crossing and stays as it is.
     */
    private List<Node> cutAtStart(List<Node> path, Node junction) {
        for (int i = 0; i < path.size(); i++) {
            if (!insideJunctionCircle(path.get(i), junction)) {
                Node inside = i == 0 ? junction : path.get(i - 1);
                List<Node> cut = new ArrayList<>(path.size() - i + 2);
                cut.add(junction);
                cut.add(circleCrossing(junction, inside, path.get(i)));
                cut.addAll(path.subList(i, path.size()));
                return cut;
            }
        }
        return path;
    }

    /** End end of a path, cut the same way as {@link #cutAtStart}. */
    private List<Node> cutAtEnd(List<Node> path, Node junction) {
        for (int i = path.size() - 1; i >= 0; i--) {
            if (!insideJunctionCircle(path.get(i), junction)) {
                Node inside = i == path.size() - 1 ? junction : path.get(i + 1);
                List<Node> cut = new ArrayList<>(path.subList(0, i + 1));
                cut.add(circleCrossing(junction, inside, path.get(i)));
                cut.add(junction);
                return cut;
            }
        }
        return path;
    }

    private boolean insideJunctionCircle(Node point, Node junction) {
        return point.distanceTo(junction) <= JUNCTION_CIRCLE_RADIUS_METERS;
    }

    /**
     * The point where the stretch from {@code inside} toward {@code outside}
     * leaves the junction circle. Bisection over the segment parameter keeps
     * the result identical from build to build.
     */
    private Node circleCrossing(Node junction, Node inside, Node outside) {
        double lo = 0;
        double hi = 1;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (insideJunctionCircle(lerp(inside, outside, mid), junction)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lerp(inside, outside, (lo + hi) / 2);
    }

    private Node lerp(Node a, Node b, double t) {
        return new Node(a.getLatitude() + t * (b.getLatitude() - a.getLatitude()),
                a.getLongitude() + t * (b.getLongitude() - a.getLongitude()));
    }

    /**
     * Fusing moves a vertex but leaves the edges' stored paths ending at the
     * old point, so a rebuilt end is terminated at the junction it was folded
     * into, the same way a freshly built segment end is.
     * <p>
     * The old point is not necessarily on this side of the kept junction. If
     * the path passes the junction before it reaches that stale point, the
     * part in between belongs to the dropped vertex and is cut away; keeping
     * it would draw the edge past the junction and back to the stale point,
     * i.e. a spur on the wrong side of the junction. A path that leads away
     * from the junction right at the stale point keeps its recorded point and
     * is terminated with a spoke, as before.
     */
    private List<Node> terminateAtStart(List<Node> path, GraphNode junction) {
        Node location = junction.getLocation();
        if (path.get(0).equals(location)) {
            return path;
        }
        double approach = closestApproach(path, location, true);
        return cutAtStart(approach < 0 ? path : pathFrom(path, approach), location);
    }

    /** End end of the path, terminated the same way as {@link #terminateAtStart}. */
    private List<Node> terminateAtEnd(List<Node> path, GraphNode junction) {
        Node location = junction.getLocation();
        if (path.get(path.size() - 1).equals(location)) {
            return path;
        }
        double approach = closestApproach(path, location, false);
        return cutAtEnd(approach < 0 ? path : pathTo(path, approach), location);
    }

    /**
     * Where the path passes the junction, walking in from one end, as a
     * fractional position in path order; -1 when it does not pass it. A pass
     * is a closest approach inside the junction circle that is neither the
     * entered end nor the path's other end: only then does the route leave the
     * stale point, pass the junction and continue. A path that keeps
     * approaching up to its far end runs to the junction, it does not pass it.
     * The walk stops at the first minimum, so a later loop back to the same
     * junction does not cut the path short.
     */
    private double closestApproach(List<Node> path, Node junction, boolean fromStart) {
        List<Node> ordered = fromStart ? path : reversed(path);
        double best = ordered.get(0).distanceTo(junction);
        double approach = -1;
        for (int i = 0; i < ordered.size() - 1; i++) {
            double[] t = new double[1];
            double distance = pointToSegmentMeters(junction, ordered.get(i), ordered.get(i + 1), t);
            if (distance < best - CLOSEST_APPROACH_EPSILON_METERS) {
                best = distance;
                approach = i + t[0];
            } else if (approach >= 0) {
                break;
            }
        }
        if (approach <= 0 || best > JUNCTION_CIRCLE_RADIUS_METERS) {
            return -1;
        }
        double position = fromStart ? approach : path.size() - 1 - approach;
        return position > 0 && position < path.size() - 1 ? position : -1;
    }

    /** The path starting at the fractional position {@code position}. */
    private List<Node> pathFrom(List<Node> path, double position) {
        List<Node> tail = new ArrayList<>();
        tail.add(pointAt(path, position));
        tail.addAll(path.subList((int) Math.floor(position) + 1, path.size()));
        return tail;
    }

    /** The path ending at the fractional position {@code position}. */
    private List<Node> pathTo(List<Node> path, double position) {
        List<Node> head = new ArrayList<>(path.subList(0, (int) Math.floor(position) + 1));
        Node end = pointAt(path, position);
        if (!end.equals(head.get(head.size() - 1))) {
            head.add(end);
        }
        return head;
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
        for (int pass = 0; ; pass++) {
            String step = null;
            RouteGraph next = fuseNearbyEndpoints(current);
            if (next != null) {
                step = "fuseNearbyEndpoints";
            }
            if (next == null) {
                next = contractSharedChains(current);
                if (next != null) {
                    step = "contractSharedChains";
                }
            }
            if (next == null) {
                next = mergeParallelEdges(current);
                if (next != null) {
                    step = "mergeParallelEdges";
                }
            }
            if (next == null) {
                next = fuseCloseJunctions(current);
                if (next != null) {
                    step = "fuseCloseJunctions";
                }
            }
            if (next == null) {
                next = splitAtOverlaps(current);
                if (next != null) {
                    step = "splitAtOverlaps";
                }
            }
            if (next == null) {
                return current;
            }
            if (pass >= MAX_SIMPLIFY_PASSES) {
                throw new IllegalStateException("simplify did not converge after "
                        + MAX_SIMPLIFY_PASSES + " passes: " + step
                        + " kept returning a new graph");
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

    /** A stretch of one edge's path that stays within tolerance of another's. */
    private record Overlap(double startPosition, double endPosition, double lengthMeters) {}

    /**
     * Two edges can run the same street for part of their length without ever
     * meeting at a vertex; two recordings of one street that join at only one
     * end are the usual case. The map then draws the stretch twice, and the
     * edges merge and split with no junction where they fork.
     * <p>
     * A stretch of one edge's path that stays within the coincidence tolerance
     * of the other's for longer than the GPS offset is such a fork. A shorter
     * coincidence is a crossing or jitter: at that scale a junction would only
     * mark noise, so it is left alone. A pair that already shares both vertices
     * needs no fork either.
     * <p>
     * The pair is cut at the two ends of the coincident stretch and
     * {@link #mergeParallelEdges} then folds the stretch into one shared edge.
     * One pair per pass, so every split gets a full round of simplification.
     */
    private RouteGraph splitAtOverlaps(RouteGraph graph) {
        List<GraphEdge> edges = graph.getEdges();
        for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
                GraphEdge first = edges.get(i);
                GraphEdge second = edges.get(j);
                if (first.getFrom() == first.getTo() || second.getFrom() == second.getTo()) {
                    continue; // self-loops follow their own path
                }
                if (sameEndpoints(first, second.getFrom(), second.getTo())) {
                    continue; // the fork between them already is a vertex
                }
                Overlap overlap = longestCoincidence(first.getPath(), second.getPath());
                if (overlap == null || overlap.lengthMeters() <= gpsOffsetMeters()) {
                    continue;
                }
                RouteGraph split = splitAtForks(graph, first, second, overlap);
                if (split != null) {
                    return split;
                }
            }
        }
        return null;
    }

    /**
     * How close two edges must stay to count as the same street. Half the
     * matching tolerance, not all of it: at the full tolerance a footpath
     * beside a road still qualifies, and merging it would fold a different way
     * onto the road and stop drawing it. Only paths that really lie on top of
     * each other are merged here.
     */
    private double coincidenceMeters() {
        return toleranceMeters / 2;
    }

    /**
     * The longest stretch of {@code path} that lies within
     * {@link #coincidenceMeters()} of {@code other} throughout, in path order;
     * null when no point of the path is that close.
     */
    private Overlap longestCoincidence(List<Node> path, List<Node> other) {
        Overlap longest = null;
        int start = -1;
        int lastInside = -1;
        double arc = 0;
        double startArc = 0;
        double lastArc = 0;
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                arc += path.get(i - 1).distanceTo(path.get(i));
            }
            if (distanceToPath(path.get(i), other) > coincidenceMeters()) {
                if (start >= 0) {
                    longest = longer(longest, new Overlap(start, lastInside, lastArc - startArc));
                    start = -1;
                }
                continue;
            }
            if (start < 0) {
                start = i;
                startArc = arc;
            }
            lastInside = i;
            lastArc = arc;
        }
        if (start >= 0) {
            return longer(longest, new Overlap(start, lastInside, lastArc - startArc));
        }
        return longest;
    }

    private Overlap longer(Overlap longest, Overlap candidate) {
        return longest == null || candidate.lengthMeters() > longest.lengthMeters()
                ? candidate : longest;
    }

    /** Distance in meters from {@code point} to the nearest spot of a path. */
    private double distanceToPath(Node point, List<Node> path) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < path.size() - 1; i++) {
            min = Math.min(min, pointToSegmentMeters(point, path.get(i), path.get(i + 1), new double[1]));
        }
        return min;
    }

    /**
     * Cuts both edges at the ends of their coincident stretch. An end where the
     * two edges already share a vertex keeps it; every other end gets a new
     * junction on the first edge's point, and the second edge is cut at its
     * closest point to that junction. Returns null when a fork would have to
     * move an edge's own end vertex: there is no point on that edge to cut at,
     * and re-terminating the edge elsewhere would rewire what it connects.
     * Also null when nothing would change — both ends already sit on shared
     * vertices, or the pieces come out identical to the source edges — because
     * a rebuild that changes nothing would make {@link #simplify} spin.
     * <p>
     * A fork closer than the GPS offset to an existing junction or to one of
     * the edges' own end vertices is treated as already present: the pair is
     * skipped. Cutting a second junction a few metres along the street would
     * leave remainders that still overlap at the new fork, and the next pass
     * would cut again, drifting junction by junction down the street.
     */
    private RouteGraph splitAtForks(RouteGraph graph, GraphEdge first, GraphEdge second,
                                    Overlap overlap) {
        List<Node> firstPath = first.getPath();
        List<Node> secondPath = second.getPath();
        List<Cut> firstCuts = new ArrayList<>();
        List<Cut> secondCuts = new ArrayList<>();
        List<GraphNode> junctions = new ArrayList<>();
        for (double position : new double[] {overlap.startPosition(), overlap.endPosition()}) {
            if (sharedEnd(first, second, position) != null) {
                continue;
            }
            if (position <= 0 || position >= firstPath.size() - 1) {
                return null;
            }
            GraphNode junction = new GraphNode(pointAt(firstPath, position), true);
            double secondPosition = closestPosition(secondPath, junction.getLocation());
            if (secondPosition <= 0 || secondPosition >= secondPath.size() - 1) {
                return null;
            }
            if (forkAlreadyPresent(graph, first, second, junction.getLocation())) {
                return null;
            }
            junctions.add(junction);
            firstCuts.add(new Cut(position, junction));
            secondCuts.add(new Cut(secondPosition, junction));
        }
        List<GraphEdge> firstPieces = cutIntoPieces(first, firstCuts);
        List<GraphEdge> secondPieces = cutIntoPieces(second, secondCuts);
        if (junctions.isEmpty()
                || (sameEdge(first, firstPieces) && sameEdge(second, secondPieces))) {
            return null;
        }
        List<GraphEdge> pieces = new ArrayList<>(firstPieces);
        pieces.addAll(secondPieces);
        return rebuild(graph, Map.of(), Set.of(), Set.of(first, second), junctions, pieces);
    }

    /**
     * A fork spot that an existing junction or one of the edges' own end
     * vertices already covers, measured in metres: two forks within the GPS
     * offset of each other are the same place.
     */
    private boolean forkAlreadyPresent(RouteGraph graph, GraphEdge first, GraphEdge second,
                                       Node fork) {
        double limit = gpsOffsetMeters();
        if (fork.distanceTo(first.getFrom().getLocation()) < limit
                || fork.distanceTo(first.getTo().getLocation()) < limit
                || fork.distanceTo(second.getFrom().getLocation()) < limit
                || fork.distanceTo(second.getTo().getLocation()) < limit) {
            return true;
        }
        return graph.getJunctions().stream()
                .anyMatch(junction -> junction.getLocation().distanceTo(fork) < limit);
    }

    /** The cut left the edge unchanged: one piece, same vertices, same shape. */
    private boolean sameEdge(GraphEdge edge, List<GraphEdge> pieces) {
        return pieces.size() == 1
                && sameEndpoints(edge, pieces.get(0).getFrom(), pieces.get(0).getTo())
                && sameGeometry(edge.getPath(), pieces.get(0).getPath());
    }

    /** The vertex a fork already sits on, when both edges end there. */
    private GraphNode sharedEnd(GraphEdge first, GraphEdge second, double position) {
        if (position <= 0) {
            return endsAt(second, first.getFrom()) ? first.getFrom() : null;
        }
        if (position >= first.getPath().size() - 1) {
            return endsAt(second, first.getTo()) ? first.getTo() : null;
        }
        return null;
    }

    private boolean endsAt(GraphEdge edge, GraphNode vertex) {
        return edge.getFrom() == vertex || edge.getTo() == vertex;
    }

    /** Fractional position of the point of a path closest to {@code point}. */
    private double closestPosition(List<Node> path, Node point) {
        double bestPosition = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < path.size() - 1; i++) {
            double[] t = new double[1];
            double distance = pointToSegmentMeters(point, path.get(i), path.get(i + 1), t);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPosition = i + t[0];
            }
        }
        return bestPosition;
    }

    /**
     * An edge cut into the pieces between its ends and the cuts, each piece
     * terminated at its ends the way a freshly built segment is.
     */
    private List<GraphEdge> cutIntoPieces(GraphEdge edge, List<Cut> cuts) {
        List<Cut> ordered = new ArrayList<>(cuts);
        ordered.sort(java.util.Comparator.comparingDouble(Cut::position));
        List<GraphEdge> pieces = new ArrayList<>();
        double position = 0;
        GraphNode vertex = edge.getFrom();
        for (Cut cut : ordered) {
            if (cut.position() > position) {
                pieces.add(piece(edge, vertex, cut.vertex(),
                        subPath(edge.getPath(), position, cut.position())));
            }
            position = cut.position();
            vertex = cut.vertex();
        }
        int end = edge.getPath().size() - 1;
        if (position < end) {
            pieces.add(piece(edge, vertex, edge.getTo(), subPath(edge.getPath(), position, end)));
        }
        return pieces;
    }

    private GraphEdge piece(GraphEdge edge, GraphNode from, GraphNode to, List<Node> path) {
        return newEdge(from, to, cutAtJunctionCircles(path, from, to),
                edge.getRoutes(), edge.getTraversals());
    }

    /** The stretch of a path between two fractional positions, built as a segment is. */
    private List<Node> subPath(List<Node> path, double from, double to) {
        List<Node> piece = new ArrayList<>();
        piece.add(pointAt(path, from));
        for (int i = (int) Math.floor(from) + 1; i <= (int) Math.ceil(to) - 1; i++) {
            piece.add(path.get(i));
        }
        piece.add(pointAt(path, to));
        return piece;
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
        return rebuild(graph, remap, removedVertices, removedEdges, List.of(), extraEdges);
    }

    private RouteGraph rebuild(RouteGraph graph, Map<GraphNode, GraphNode> remap,
                               Set<GraphNode> removedVertices, Set<GraphEdge> removedEdges,
                               List<GraphNode> extraVertices, List<GraphEdge> extraEdges) {
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
            List<Node> path = edge.getPath();
            if (from != edge.getFrom()) {
                path = terminateAtStart(path, from);
            }
            if (to != edge.getTo()) {
                path = terminateAtEnd(path, to);
            }
            result.addEdge(newEdge(from, to, path, edge.getRoutes(), edge.getTraversals()));
        }
        extraVertices.forEach(result::addExisting);
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
