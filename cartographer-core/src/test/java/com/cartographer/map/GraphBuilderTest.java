package com.cartographer.map;

import com.cartographer.model.Node;
import com.cartographer.model.Route;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphBuilderTest {

    private static final double TOLERANCE = 10.0;
    // GraphBuilder cuts edge ends at this radius around a junction coordinate
    private static final double JUNCTION_RADIUS = 5.0;
    // 0.001 deg latitude ~ 111 m

    private Route route(Node... nodes) {
        Route route = new Route();
        for (Node node : nodes) {
            route.addNode(node);
        }
        return route;
    }

    private RouteGraph build(List<Route> routes) {
        return build(routes, TOLERANCE);
    }

    private RouteGraph build(List<Route> routes, double tolerance) {
        List<Intersection> intersections = new IntersectionFinder(tolerance).find(routes);
        return new GraphBuilder(tolerance).build(routes, intersections);
    }

    /** Builds from explicit intersections, so a test can place cuts itself. */
    private RouteGraph buildWith(List<Route> routes, List<Intersection> intersections) {
        return new GraphBuilder(TOLERANCE).build(routes, intersections);
    }

    /** Edge path oriented so that it starts at {@code vertex}. */
    private List<Node> pathFrom(GraphEdge edge, GraphNode vertex) {
        List<Node> path = new ArrayList<>(edge.getPath());
        if (edge.getFrom() != vertex) {
            Collections.reverse(path);
        }
        return path;
    }

    @Test
    void junctionEdgesEndAtTheJunctionCoordinateWithACircleCrossing() {
        Node start = new Node(48.0000, 13.0);
        Node junctionPoint = new Node(48.0010, 13.0);
        Node finish = new Node(48.0030, 13.0);
        Route a = route(start, junctionPoint, finish);
        Route b = route(junctionPoint, new Node(48.0005, 13.0008));

        RouteGraph graph = build(List.of(a, b));

        assertEquals(1, graph.getJunctions().size());
        GraphNode junction = graph.getJunctions().get(0);
        assertEquals(junctionPoint, junction.getLocation());
        assertEquals(3, graph.edgesFrom(junction).size());
        for (GraphEdge edge : graph.edgesFrom(junction)) {
            List<Node> path = pathFrom(edge, junction);
            assertEquals(junction.getLocation(), path.get(0),
                    () -> "edge must start at the junction coordinate: " + path);
            assertEquals(JUNCTION_RADIUS, path.get(1).distanceTo(junction.getLocation()), 0.05,
                    () -> "the second point must sit on the junction circle: " + path);
            for (Node node : path.subList(2, path.size())) {
                assertTrue(node.distanceTo(junction.getLocation()) > JUNCTION_RADIUS - 0.05,
                        () -> "points inside the junction circle must be cut away: " + path);
            }
        }
    }

    @Test
    void nonJunctionEndsKeepTheirRecordedTrackPoints() {
        Node start = new Node(48.0000, 13.0);
        Node junctionPoint = new Node(48.0010, 13.0);
        Node finish = new Node(48.0030, 13.0);
        Route a = route(start, junctionPoint, finish);
        Route b = route(new Node(48.0005, 13.0008), junctionPoint);

        RouteGraph graph = build(List.of(a, b));

        for (GraphEdge edge : graph.getEdges()) {
            // Every end meets its vertex: junction ends at the junction
            // coordinate, route starts and finishes at the recorded point.
            assertEquals(edge.getFrom().getLocation(), edge.getPath().get(0),
                    () -> "edge must start at its from-vertex: " + edge);
            assertEquals(edge.getTo().getLocation(), edge.getPath().get(edge.getPath().size() - 1),
                    () -> "edge must end at its to-vertex: " + edge);
        }
        GraphNode junction = graph.getJunctions().get(0);
        for (GraphEdge edge : graph.edgesFrom(junction)) {
            GraphNode other = edge.otherEnd(junction);
            assertFalse(other.isJunction());
            List<Node> path = pathFrom(edge, other);
            assertEquals(other.getLocation(), path.get(0));
        }
    }

    @Test
    void edgeFullyInsideTheJunctionCircleIsUnchanged() {
        // A junction circle wider than the matching tolerance is what makes
        // this case possible: a point can lie inside the circle and still be
        // too far from every cluster point to join the cluster. A narrow
        // tolerance keeps the cluster tight while the 5 m circle reaches past
        // it, so the whole spur of route c lies inside the circle and there is
        // nothing to cut.
        double tolerance = 2.0;
        Node p0 = new Node(48.0000, 13.0);
        Node clusterPointA = new Node(48.0010, 13.0);
        Node clusterPointB = new Node(48.00101441, 13.0); // ~1.6 m north
        Node p2 = new Node(48.0030, 13.0);
        Route a = route(p0, clusterPointA, p2);
        Route b = route(new Node(48.0000, 13.0008), clusterPointB, new Node(48.0025, 13.0008));
        Node insideEnd = new Node(48.00100720, 13.00005370); // ~4 m east of the centroid
        Route c = route(insideEnd, clusterPointA);

        RouteGraph graph = build(List.of(a, b, c), tolerance);

        assertEquals(1, graph.getJunctions().size());
        GraphNode junction = graph.getJunctions().get(0);
        Node centroid = junction.getLocation();
        assertTrue(insideEnd.distanceTo(centroid) < JUNCTION_RADIUS,
                "route c's end must lie inside the junction circle");
        assertTrue(insideEnd.distanceTo(clusterPointA) > tolerance
                        && insideEnd.distanceTo(clusterPointB) > tolerance,
                "route c's end must stay outside the junction cluster");

        GraphEdge spur = graph.edgesFrom(junction).stream()
                .filter(edge -> edge.otherEnd(junction).getLocation().equals(insideEnd))
                .findFirst().orElseThrow();
        assertEquals(List.of(clusterPointA, insideEnd), pathFrom(spur, junction),
                "a spur whose whole path is inside the circle keeps its track points");
    }

    @Test
    void simplifiedEdgeEndsFollowFusedVertices() {
        Node junctionPoint = new Node(48.0010, 13.0);
        Route a = route(new Node(48.0000, 13.0), junctionPoint, new Node(48.0020, 13.0));
        Route b = route(junctionPoint, new Node(48.0030, 13.0));
        // loop route whose start/finish sit ~22 m from the junction: too far
        // to join the cluster, close enough to be the same place (fused)
        Node loopStart = new Node(48.0010, 13.00030);
        Node loopEnd = new Node(48.0010, 13.00036);
        Route loop = route(loopStart, new Node(48.0025, 13.00030), loopEnd);
        // two junctions ~17 m apart on the same street: one fused into the other
        Node nearA = new Node(48.00500, 13.0);
        Node nearB = new Node(48.00515, 13.0);
        Route d = route(new Node(48.00400, 13.0), nearA, nearB, new Node(48.00600, 13.0));
        Route e = route(new Node(48.00400, 13.0006), new Node(48.00500, 13.0),
                new Node(48.00515, 13.0), new Node(48.00600, 13.0006));

        RouteGraph simplified = new GraphBuilder(TOLERANCE)
                .simplify(build(List.of(a, b, loop, d, e)));

        assertEquals(2, simplified.getJunctions().size(),
                () -> "vertices: " + simplified.getNodes());
        for (GraphEdge edge : simplified.getEdges()) {
            List<Node> path = edge.getPath();
            assertEquals(edge.getFrom().getLocation(), path.get(0),
                    () -> "fused edge must start at its vertex: " + edge);
            assertEquals(edge.getTo().getLocation(), path.get(path.size() - 1),
                    () -> "fused edge must end at its vertex: " + edge);
        }
        // folding the loop's start into the junction keeps the recorded point
        // as the outer end of the spoke
        assertTrue(simplified.getEdges().stream()
                        .anyMatch(edge -> edge.getPath().contains(loopStart)),
                () -> "the folded point should stay in the path: " + simplified.getEdges());
    }

    @Test
    void reTerminatedEdgeEndIsCutAtTheKeptJunctionInsteadOfLoopedBack() {
        // The street is cut at a second junction 17.7 m northeast of the real
        // one, at its own sample 10 m before it. The edge ending there
        // approaches along the street, then takes a spoke to that stale vertex
        // which passes the real junction and ends behind it. Fusing the two
        // junctions must shorten the edge at the real junction: the stale
        // vertex lies beyond it, and everything up to it is that detour.
        double lonStep = 0.000135; // ~10 m per sample at this latitude
        List<Node> samples = new ArrayList<>();
        for (int i = -20; i <= 20; i++) {
            samples.add(new Node(48.6, 13.7 + i * lonStep));
        }
        Node realJunction = samples.get(20);
        Node stale = new Node(48.60011229, 13.70016981); // 17.7 m northeast
        Route street = route(samples.toArray(new Node[0]));
        Route south = route(realJunction, new Node(48.5990, 13.7));
        Route west = route(realJunction, new Node(48.6, 13.699));
        Route east = route(stale, new Node(48.6001, 13.7010));
        Intersection kept = new Intersection(realJunction, List.of(
                new RoutePoint(street, 20, realJunction),
                new RoutePoint(south, 0, realJunction),
                new RoutePoint(west, 0, realJunction)));
        Intersection folded = new Intersection(stale, List.of(
                new RoutePoint(street, 19, samples.get(19)),
                new RoutePoint(east, 0, stale)));

        RouteGraph graph = new GraphBuilder(TOLERANCE)
                .simplify(buildWith(List.of(street, south, west, east), List.of(kept, folded)));

        assertEquals(1, graph.getJunctions().size(), () -> "vertices: " + graph.getNodes());
        GraphNode junction = graph.getJunctions().get(0);
        assertEquals(realJunction, junction.getLocation(), "the kept vertex must win the fusion");
        GraphEdge approach = graph.edgesFrom(junction).stream()
                .filter(edge -> edge.getRoutes().contains(street))
                .filter(edge -> edge.otherEnd(junction).getLocation().equals(samples.get(0)))
                .findFirst().orElseThrow(() -> new AssertionError("edges: " + graph.getEdges()));
        List<Node> path = pathFrom(approach, junction);
        assertEquals(junction.getLocation(), path.get(0),
                () -> "the edge must end at the kept junction: " + path);
        assertFalse(path.contains(stale),
                () -> "the stale vertex coordinate must be cut away: " + path);
        assertTrue(path.stream()
                        .allMatch(node -> node.getLongitude() <= junction.getLocation().getLongitude()),
                () -> "the edge must not overshoot the junction eastward: " + path);
    }

    @Test
    void reTerminatedEdgeStartIsCutAtTheKeptJunctionInsteadOfLoopedBack() {
        // Mirror case: the stale junction sits 17.7 m southwest of the kept
        // one, and the street leaves it eastward straight through the kept
        // junction. Re-termination must start the edge at the kept junction
        // and keep only the stretch on its far side.
        double lonStep = 0.000135;
        List<Node> samples = new ArrayList<>();
        for (int i = -20; i <= 20; i++) {
            samples.add(new Node(48.6, 13.7 + i * lonStep));
        }
        Node realJunction = samples.get(20);
        Node stale = new Node(48.59988771, 13.69983019); // 17.7 m southwest
        Route street = route(samples.toArray(new Node[0]));
        Route north = route(realJunction, new Node(48.6010, 13.7));
        Route northwest = route(realJunction, new Node(48.6009, 13.699));
        Route south = route(stale, new Node(48.5990, 13.69983019));
        Intersection kept = new Intersection(realJunction, List.of(
                new RoutePoint(street, 20, realJunction),
                new RoutePoint(north, 0, realJunction),
                new RoutePoint(northwest, 0, realJunction)));
        Intersection folded = new Intersection(stale, List.of(
                new RoutePoint(street, 21, samples.get(21)),
                new RoutePoint(south, 0, stale)));

        RouteGraph graph = new GraphBuilder(TOLERANCE)
                .simplify(buildWith(List.of(street, north, northwest, south), List.of(kept, folded)));

        assertEquals(1, graph.getJunctions().size(), () -> "vertices: " + graph.getNodes());
        GraphNode junction = graph.getJunctions().get(0);
        assertEquals(realJunction, junction.getLocation(), "the kept vertex must win the fusion");
        GraphEdge departure = graph.edgesFrom(junction).stream()
                .filter(edge -> edge.getRoutes().contains(street))
                .filter(edge -> edge.otherEnd(junction).getLocation()
                        .equals(samples.get(samples.size() - 1)))
                .findFirst().orElseThrow(() -> new AssertionError("edges: " + graph.getEdges()));
        List<Node> path = pathFrom(departure, junction);
        assertEquals(junction.getLocation(), path.get(0),
                () -> "the edge must start at the kept junction: " + path);
        assertFalse(path.contains(stale),
                () -> "the stale vertex coordinate must be cut away: " + path);
        assertTrue(path.stream()
                        .allMatch(node -> node.getLongitude() >= junction.getLocation().getLongitude()),
                () -> "the edge must not overshoot the junction westward: " + path);
    }

    @Test
    void buildsFromTheSameRoutesAreIdentical() {
        Route a = route(new Node(48.0000, 13.0), new Node(48.0010, 13.0),
                new Node(48.0020, 13.0), new Node(48.0030, 13.0));
        Route b = route(new Node(48.0025, 13.0008), new Node(48.0020, 13.0),
                new Node(48.0010, 13.0), new Node(48.0010, 13.0006));

        RouteGraph first = build(List.of(a, b));
        RouteGraph second = build(List.of(a, b));

        assertEquals(first.getEdges().size(), second.getEdges().size());
        for (int i = 0; i < first.getEdges().size(); i++) {
            List<Node> expected = first.getEdges().get(i).getPath();
            List<Node> actual = second.getEdges().get(i).getPath();
            assertEquals(expected.size(), actual.size(), "edge " + i + " point count");
            for (int j = 0; j < expected.size(); j++) {
                assertEquals(expected.get(j), actual.get(j),
                        "edge " + i + " point " + j);
            }
        }
    }

    @Test
    void disjointRoutesBecomeSeparateComponents() {
        Route a = route(new Node(48.0, 13.0), new Node(48.001, 13.0));
        Route b = route(new Node(49.0, 14.0), new Node(49.001, 14.0));

        RouteGraph graph = build(List.of(a, b));

        assertEquals(4, graph.getNodes().size());
        assertEquals(0, graph.getJunctions().size());
        assertEquals(2, graph.getEdges().size());
        assertFalse(graph.getEdges().get(0).isShared());
    }

    @Test
    void sharedStretchIsMergedIntoSharedEdges() {
        // a and b overlap (in opposite directions) between (48.001) and (48.003)
        Route a = route(
                new Node(48.0000, 13.0),
                new Node(48.0010, 13.0),
                new Node(48.0020, 13.0),
                new Node(48.0030, 13.0),
                new Node(48.0040, 13.0));
        Route b = route(
                new Node(48.0050, 13.0),
                new Node(48.0030, 13.0),
                new Node(48.0020, 13.0),
                new Node(48.0010, 13.0),
                new Node(48.0005, 13.0));

        RouteGraph graph = build(List.of(a, b));

        assertEquals(3, graph.getJunctions().size());
        assertEquals(4, graph.getNodes().size() - graph.getJunctions().size());

        assertEquals(6, graph.getEdges().size());
        List<GraphEdge> shared = graph.getEdges().stream().filter(GraphEdge::isShared).toList();
        assertEquals(2, shared.size());
        for (GraphEdge edge : shared) {
            assertTrue(edge.getRoutes().containsAll(List.of(a, b)));
        }
    }

    @Test
    void adjacencyConnectsVerticesThroughEdges() {
        Route a = route(new Node(48.0, 13.0), new Node(48.001, 13.0), new Node(48.002, 13.0));
        Route b = route(new Node(48.001, 13.0), new Node(48.0005, 13.001));

        RouteGraph graph = build(List.of(a, b));

        assertEquals(1, graph.getJunctions().size());
        GraphNode junction = graph.getJunctions().get(0);
        // junction connects: to a's start, a's end, and b's end
        assertEquals(3, graph.edgesFrom(junction).size());
    }

    @Test
    void simplifyContractsIntermediateJunctionsAlongSharedStreet() {
        // a and b travel the same street (in opposite directions) through
        // junction points j1..j4; j2 and j3 are intermediate waypoints
        Node j1 = new Node(48.0010, 13.0);
        Node j2 = new Node(48.0020, 13.0);
        Node j3 = new Node(48.0030, 13.0);
        Node j4 = new Node(48.0040, 13.0);
        Route a = route(new Node(48.0000, 13.0), j1, j2, j3, j4, new Node(48.0050, 13.0));
        Route b = route(new Node(48.0060, 13.0), j4, j3, j2, j1, new Node(48.0005, 13.0008));

        RouteGraph raw = build(List.of(a, b));
        assertEquals(4, raw.getJunctions().size());

        RouteGraph simplified = new GraphBuilder(TOLERANCE).simplify(raw);

        assertEquals(2, simplified.getJunctions().size());
        List<GraphEdge> shared = simplified.getEdges().stream().filter(GraphEdge::isShared).toList();
        assertEquals(1, shared.size());
        assertEquals(2, shared.get(0).getRoutes().size());
        // the contracted street keeps its physical length: j1..j4 ~ 333 m
        assertEquals(333, shared.get(0).getLengthMeters(), 5);
    }

    @Test
    void simplifyFusesEndpointsNextToJunctions() {
        Node junction = new Node(48.0010, 13.0);
        Route a = route(new Node(48.0000, 13.0), junction, new Node(48.0020, 13.0));
        Route b = route(junction, new Node(48.0030, 13.0));
        // loop-like route whose start/finish sit ~22 m from the junction
        // (too far to intersect, close enough to be the same place)
        Route c = route(new Node(48.001, 13.0003), new Node(48.0025, 13.0003),
                new Node(48.001, 13.00036));

        RouteGraph raw = build(List.of(a, b, c));
        assertEquals(5, raw.getNodes().size() - raw.getJunctions().size());

        RouteGraph simplified = new GraphBuilder(TOLERANCE).simplify(raw);

        assertEquals(1, simplified.getJunctions().size());
        // both loop endpoints folded away; only a's endpoints and b's end remain
        assertEquals(3, simplified.getNodes().size() - simplified.getJunctions().size());
        GraphNode j = simplified.getJunctions().get(0);
        // 3 spoke edges + the loop route as a self-loop cycle on the junction
        assertEquals(4, simplified.edgesFrom(j).size());
        assertTrue(simplified.edgesFrom(j).stream()
                .anyMatch(e -> e.getFrom() == e.getTo() && e.getLengthMeters() > TOLERANCE),
                "expected the loop route to become a cycle on the junction");
    }

    @Test
    void simplifyLeavesTrueJunctionsAlone() {
        // three routes meet at one point: degree-3 junction must survive
        Route a = route(new Node(48.0, 13.0), new Node(48.001, 13.0));
        Route b = route(new Node(48.001, 13.0), new Node(48.002, 13.0));
        Route c = route(new Node(48.001, 13.0001), new Node(48.001, 13.0006));
        Route d = route(new Node(48.001, 13.0), new Node(48.0, 13.001));

        RouteGraph simplified = new GraphBuilder(TOLERANCE)
                .simplify(build(List.of(a, b, c, d)));

        assertEquals(1, simplified.getJunctions().size());
        assertEquals(4, simplified.edgesFrom(simplified.getJunctions().get(0)).size());
    }

    @Test
    void sparseRouteIsCutAtJunctionsItPasses() {
        Node j1 = new Node(48.0010, 13.0);
        Node j2 = new Node(48.0030, 13.0);
        Node mid = new Node(48.0020, 13.0005); // 60 m off the direct line
        // dense route: has a track point mid-way (junction forms there later)
        Route denseA = route(new Node(48.0000, 13.0), j1, mid, j2, new Node(48.0040, 13.0));
        Route denseB = route(new Node(48.0050, 13.0), j2, mid, j1, new Node(48.0005, 13.0008));
        // sparse route: same street, no track point between j1 and j2
        Route sparse = route(new Node(48.0000, 13.002), j1, j2, new Node(48.0035, 13.002));

        RouteGraph graph = new GraphBuilder(TOLERANCE).simplify(build(List.of(denseA, denseB, sparse)));

        // the sparse route was cut at the mid junction, so the whole street
        // merged: junctions j1, j2 remain (each has route tails), the
        // intermediate junction contracted away
        assertEquals(2, graph.getJunctions().size());
        List<GraphEdge> street = graph.getEdges().stream().filter(GraphEdge::isShared).toList();
        assertEquals(1, street.size(), () -> "edges: " + graph.getEdges());
        assertEquals(3, street.get(0).getRoutes().size());
    }

    @Test
    void sparseStreetIsCutAtItsOwnJunctionOnly() {
        // The street is sampled every 15 m. Another route records a junction
        // 2 m from sample 5, so the street is cut there. A second junction
        // 12.8 m away projects onto the same spot of the street, 6.5 m past
        // that cut. Cutting it again would tear the edge apart one sample
        // further on, where the junction does not reach, and the drawn edge
        // would pass the junction and come back to it.
        Node[] samples = new Node[9];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = new Node(48.0 + i * 0.000135, 13.0);
        }
        Route street = route(samples);
        Route neighbour = route(new Node(48.000675, 13.0000269), // 2 m east of sample 5
                new Node(48.000675, 13.0005));
        Node nearby = new Node(48.0007334, 13.0001745); // 6.5 m north, 13 m east of sample 5
        Route first = route(nearby, new Node(48.0007334, 13.001));
        Route second = route(new Node(48.0007334, 13.0001746), new Node(48.0007334, 12.999));

        RouteGraph graph = build(List.of(street, neighbour, first, second));

        assertEquals(2, graph.getJunctions().size(), () -> "vertices: " + graph.getNodes());
        for (GraphNode junction : graph.getJunctions()) {
            boolean ownCut = junction.getLocation().distanceTo(samples[5]) < TOLERANCE;
            boolean carriesStreet = graph.edgesFrom(junction).stream()
                    .anyMatch(edge -> edge.getRoutes().contains(street));
            assertEquals(ownCut, carriesStreet,
                    () -> "the street is cut at its own junction only: " + junction);
        }
        // start -> cut -> finish, with no cut in between
        assertEquals(2, graph.getEdges().stream()
                        .filter(edge -> edge.getRoutes().contains(street)).count(),
                () -> "edges: " + graph.getEdges());
    }

    @Test
    void denseStreetIsCutOnceWhereTwoJunctionsProject() {
        // One sample per metre. The junction 9 m west of sample 10 pulls
        // samples 6..14 into one junction; the second junction projects 1.5 m
        // past that run, i.e. onto the same spot of the street, though it is
        // 1.5 samples away. The index distance says nothing about how far
        // apart two cuts are, the tolerance does.
        Node[] samples = new Node[21];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = new Node(48.0 + i * 0.000009, 13.0);
        }
        Route street = route(samples);
        Route neighbour = route(new Node(48.00009, 12.999879), // 9 m west of sample 10
                new Node(48.00009, 12.9994));
        Node nearby = new Node(48.0001395, 13.0001747); // 1.5 m north of sample 15, 13 m east
        Route first = route(nearby, new Node(48.0001395, 13.001));
        Route second = route(new Node(48.0001396, 13.0001747), new Node(48.0001395, 12.999));

        RouteGraph graph = build(List.of(street, neighbour, first, second));

        assertEquals(2, graph.getJunctions().size(), () -> "vertices: " + graph.getNodes());
        for (GraphNode junction : graph.getJunctions()) {
            boolean ownCut = junction.getLocation().distanceTo(samples[10]) < TOLERANCE;
            boolean carriesStreet = graph.edgesFrom(junction).stream()
                    .anyMatch(edge -> edge.getRoutes().contains(street));
            assertEquals(ownCut, carriesStreet,
                    () -> "the street is cut at its own junction only: " + junction);
        }
        assertEquals(2, graph.getEdges().stream()
                        .filter(edge -> edge.getRoutes().contains(street)).count(),
                () -> "edges: " + graph.getEdges());
    }

    @Test
    void sparseSamplesAreNotCutWhereTheJunctionLiesBeyondTheSegment() {
        // Samples 60 m apart, the junction 20 m beside sample 2: the closest
        // point of the track is that sample itself, and the projection is
        // clamped to it. The junction does not touch the street there, so a
        // cut at the sample would move the junction by 20 m.
        Node[] samples = new Node[5];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = new Node(48.0 + i * 0.00054, 13.0);
        }
        Route street = route(samples);
        Node beside = new Node(48.00108, 13.0002688); // 20 m east of sample 2
        Route first = route(beside, new Node(48.00108, 13.001));
        Route second = route(new Node(48.00108, 13.0002689), new Node(48.0025, 13.0002688));

        RouteGraph graph = build(List.of(street, first, second));

        assertEquals(1, graph.getJunctions().size(), () -> "vertices: " + graph.getNodes());
        GraphNode junction = graph.getJunctions().get(0);
        assertTrue(graph.edgesFrom(junction).stream()
                        .noneMatch(edge -> edge.getRoutes().contains(street)),
                () -> "the street is not cut at the clamped projection: " + junction);
        assertEquals(1, graph.getEdges().stream()
                        .filter(edge -> edge.getRoutes().contains(street)).count(),
                () -> "the street stays one edge: " + graph.getEdges());
    }

    @Test
    void genuinelyDifferentParallelPathsStaySeparate() {
        Node j1 = new Node(48.0010, 13.0);
        Node j2 = new Node(48.0020, 13.0);
        // two routes on the direct street
        Route a = route(new Node(48.0000, 13.0), j1, j2, new Node(48.0030, 13.0));
        Route b = route(new Node(48.0005, 13.002), j1, j2, new Node(48.0025, 13.002));
        // one route taking a ~250 m detour between the same junctions
        Route detour = route(new Node(48.0, 13.003), j1,
                new Node(48.0015, 13.0015), j2, new Node(48.003, 13.003));

        RouteGraph graph = new GraphBuilder(TOLERANCE).simplify(build(List.of(a, b, detour)));

        long streetEdges = graph.getEdges().stream()
                .filter(e -> (e.getFrom().isJunction() && e.getTo().isJunction()))
                .count();
        assertEquals(2, streetEdges, () -> "edges: " + graph.getEdges());
        assertTrue(graph.getEdges().stream().anyMatch(e -> e.getRoutes().size() == 2),
                "the direct street should be shared by a and b");
        assertTrue(graph.getEdges().stream().anyMatch(e -> e.getLengthMeters() > 200),
                "the detour path should remain its own edge");
    }

    @Test
    void junctionsWithinGpsOffsetAreFused() {
        // two cross-route junctions 15 m apart: same trailhead in reality
        Node j1a = new Node(48.0010, 13.0);
        Node j1b = new Node(48.0010, 13.0002); // ~15 m east
        Node j2 = new Node(48.0040, 13.0);
        Route a = route(new Node(48.0000, 13.0), j1a, j1b, j2);
        Route b = route(new Node(48.0002, 13.001), j1a, j1b, j2, new Node(48.0050, 13.0));

        RouteGraph simplified = new GraphBuilder(TOLERANCE)
                .simplify(build(List.of(a, b)));

        assertEquals(2, simplified.getJunctions().size(),
                () -> "vertices: " + simplified.getNodes());
        assertTrue(simplified.getEdges().stream()
                .noneMatch(e -> e.getLengthMeters() < TOLERANCE),
                "the 15 m stub between the fused junctions should be gone");
    }

    @Test
    void outAndBackStreetCountsAsTraversedTwice() {
        // one route, out along a street, around a small loop, back the
        // same street; the two strands must merge into one shared edge
        List<Node> nodes = new ArrayList<>(List.of(
                new Node(48.0004, 13.0),           // 0 start
                new Node(48.0010, 13.0),           // 1 street junction A, out
                new Node(48.0015, 13.0),           // 2
                new Node(48.0020, 13.0),           // 3 street junction B, out
                new Node(48.0025, 13.0004),        // 4 loop leg
                new Node(48.0030, 13.0008),        // 5 loop
                new Node(48.0026, 13.0012),        // 6 loop
                new Node(48.0022, 13.0009),        // 7 loop leg
                new Node(48.0021, 13.0004),        // 8 toward B
                new Node(48.0020, 13.00008),       // 9 street junction B, back (6m from #3)
                new Node(48.0015, 13.00008),       // 10 street back
                new Node(48.0010, 13.00008),       // 11 street junction A, back (6m from #1)
                new Node(48.0005, 13.0008)));      // 12 end
        Route loop = route(nodes.toArray(new Node[0]));

        List<Intersection> intersections =
                new IntersectionFinder(TOLERANCE).findWithSelfIntersections(List.of(loop));
        RouteGraph graph = new GraphBuilder(TOLERANCE)
                .simplify(new GraphBuilder(TOLERANCE).build(List.of(loop), intersections));

        assertEquals(2, graph.getJunctions().size(), () -> "vertices: " + graph.getNodes());
        List<GraphEdge> street = graph.getEdges().stream().filter(GraphEdge::isShared).toList();
        assertEquals(1, street.size(), () -> "edges: " + graph.getEdges());
        assertEquals(2, street.get(0).getTraversals());
        // the small loop at the far end becomes a cycle on junction B
        assertTrue(graph.getEdges().stream().anyMatch(e -> e.getFrom().isJunction()
                && e.getFrom() == e.getTo() && e.getLengthMeters() > 100));
    }

    @Test
    void retracedStreetContractsWithoutLosingLength() {
        // out-and-back along a street whose out/return points pair up into
        // junctions only ~28 m apart: junction fusion must not eat the
        // street; contraction turns it into one twice-traveled edge
        List<Node> nodes = new ArrayList<>(List.of(
                new Node(48.0004, 13.0),           // 0 start, ~75 m before the street
                new Node(48.0010, 13.0),           // 1 junction J1, out
                new Node(48.00125, 13.0),          // 2 junction J2, out (~28 m)
                new Node(48.0015, 13.0),           // 3 junction J3, out (~28 m)
                new Node(48.0020, 13.0004),        // 4-11 tip excursion
                new Node(48.0025, 13.0008),
                new Node(48.0028, 13.0012),
                new Node(48.0025, 13.0016),
                new Node(48.0021, 13.0014),
                new Node(48.0018, 13.0010),
                new Node(48.0016, 13.0006),
                new Node(48.00155, 13.0002),
                new Node(48.0015, 13.00008),       // 12 junction J3, back (~6 m from #3)
                new Node(48.00125, 13.00008),      // 13 junction J2, back
                new Node(48.0010, 13.00008),       // 14 junction J1, back
                new Node(48.0005, 13.0008)));      // 15 end
        Route loop = route(nodes.toArray(new Node[0]));

        List<Intersection> intersections =
                new IntersectionFinder(TOLERANCE).findWithSelfIntersections(List.of(loop));
        RouteGraph graph = new GraphBuilder(TOLERANCE)
                .simplify(new GraphBuilder(TOLERANCE).build(List.of(loop), intersections));

        assertEquals(2, graph.getJunctions().size(), () -> "vertices: " + graph.getNodes());
        List<GraphEdge> street = graph.getEdges().stream().filter(GraphEdge::isShared).toList();
        assertEquals(1, street.size(), () -> "edges: " + graph.getEdges());
        assertEquals(2, street.get(0).getTraversals());
        assertEquals(56, street.get(0).getLengthMeters(), 10,
                () -> "street length must survive: " + street.get(0));
    }

    @Test
    void denseOutAndBackBecomesOneSharedCorridorEdge() {
        // a time-dense out-and-back walk: ~185 m spur K->S, corridor S->T
        // sampled every ~1 m, a ~60 m turnaround excursion past T, the same
        // corridor back, then a ~185 m spur S->L. Both legs stay within
        // tolerance along the whole street, so they chain into one cluster
        // that the corridor-end junctions cut apart: the two legs must merge
        // into a single edge traveled twice, the street counted once.
        Route walk = route(denseOutAndBackNodes().toArray(new Node[0]));

        List<Intersection> intersections =
                new IntersectionFinder(TOLERANCE).findWithSelfIntersections(List.of(walk));
        RouteGraph graph = new GraphBuilder(TOLERANCE)
                .simplify(new GraphBuilder(TOLERANCE).build(List.of(walk), intersections));

        // spur K->S, corridor, turnaround stub, spur S->L: no second street
        List<GraphEdge> shared = graph.getEdges().stream().filter(GraphEdge::isShared).toList();
        assertEquals(1, shared.size(), () -> "edges: " + graph.getEdges());
        assertEquals(4, graph.getEdges().size(), () -> "edges: " + graph.getEdges());
        assertEquals(2, shared.get(0).getTraversals());
        assertEquals(1, shared.get(0).getRoutes().size());
        // one pass over the 100 m street, extended by the legs that chain
        // around the turnaround, not the two legs' 200 m side by side. The
        // junction-circle cut straightens the ends, so the street is a bit
        // shorter than its raw track.
        assertEquals(138, shared.get(0).getLengthMeters(), 5,
                () -> "one street length, not two: " + shared.get(0));
        // 185 m spur + 138 m corridor + 76 m turnaround stub + 183 m spur;
        // the spurs keep a spoke to their junction coordinate, so each is a
        // little longer than the recorded leg
        assertEquals(582, graph.totalLengthMeters(), 15,
                () -> "street counted twice: " + graph.getEdges());
    }

    /** Spur K->S, corridor S->T and back, spur S->L; ~1 m between points. */
    private List<Node> denseOutAndBackNodes() {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i <= 185; i++) {
            nodes.add(new Node(48.6000, 13.7000 + i * 0.0000135));
        }
        for (int i = 1; i <= 100; i++) {
            nodes.add(new Node(48.6000 + i * 0.000009, 13.7025));
        }
        for (int i = 1; i <= 60; i++) { // ~60 m excursion east of T
            nodes.add(new Node(48.6009, 13.7025 + i * 0.0000135));
        }
        for (int i = 59; i >= 0; i--) {
            nodes.add(new Node(48.6009, 13.7025 + i * 0.0000135));
        }
        for (int i = 1; i <= 100; i++) {
            nodes.add(new Node(48.6009 - i * 0.000009, 13.70255));
        }
        for (int i = 1; i <= 185; i++) {
            nodes.add(new Node(48.6000, 13.7025 + i * 0.0000135));
        }
        return nodes;
    }

    @Test
    void partiallyOverlappingEdgesAreCutAtBothForks() {
        // Two recordings of one street that never meet at a vertex: b joins
        // the street 4 m off its centre line, stays on it for ~220 m and
        // leaves again. Without a fork the map draws the shared stretch
        // twice, and the two edges merge and split with no junction.
        List<Node> street = streetNodes();
        Route a = route(street.toArray(new Node[0]));
        List<Node> joining = new ArrayList<>();
        joining.add(new Node(48.0020, 13.00054)); // 40 m east of the street
        joining.add(new Node(48.0020, 13.000054)); // 4 m off the centre line
        for (int i = 21; i <= 40; i++) {
            joining.add(offsetEast(street.get(i), 4));
        }
        joining.add(new Node(48.0040, 13.00054));
        joining.add(new Node(48.0040, 13.0010));
        Route b = route(joining.toArray(new Node[0]));

        RouteGraph graph = new GraphBuilder(TOLERANCE).simplify(buildWith(List.of(a, b), List.of()));

        assertEquals(2, graph.getJunctions().size(), () -> "vertices: " + graph.getNodes());
        assertTrue(graph.getJunctions().stream()
                        .allMatch(j -> j.getLocation().equals(street.get(20))
                                || j.getLocation().equals(street.get(40))),
                () -> "the forks are the ends of the shared stretch: " + graph.getJunctions());
        List<GraphEdge> shared = graph.getEdges().stream().filter(GraphEdge::isShared).toList();
        assertEquals(1, shared.size(), () -> "edges: " + graph.getEdges());
        assertTrue(shared.get(0).getRoutes().containsAll(List.of(a, b)),
                () -> "one edge carries both routes: " + shared.get(0));
        assertTrue(List.of(shared.get(0).getFrom(), shared.get(0).getTo())
                        .containsAll(graph.getJunctions()),
                () -> "the shared edge runs between the two forks: " + shared.get(0));
        assertEquals(222, shared.get(0).getLengthMeters(), 10,
                () -> "the shared stretch is one street length: " + shared.get(0));
    }

    @Test
    void coincidenceShorterThanTheGpsOffsetIsLeftAlone() {
        // Same shape, but b only brushes the street for ~22 m and leaves it:
        // a crossing explains a coincidence that short, so no fork is cut.
        List<Node> street = streetNodes();
        Route a = route(street.toArray(new Node[0]));
        Route b = route(new Node(48.0030, 13.00054), offsetEast(street.get(30), 4),
                offsetEast(street.get(31), 4), offsetEast(street.get(32), 4),
                new Node(48.0032, 13.00054), new Node(48.0032, 13.0010));

        RouteGraph graph = new GraphBuilder(TOLERANCE).simplify(buildWith(List.of(a, b), List.of()));

        assertEquals(0, graph.getJunctions().size(), () -> "vertices: " + graph.getNodes());
        assertEquals(2, graph.getEdges().size(), () -> "edges: " + graph.getEdges());
        assertTrue(graph.getEdges().stream().noneMatch(GraphEdge::isShared));
    }

    @Test
    void edgesThatCrossAtOnePointAreLeftAlone() {
        // b crosses the street between two of its samples: the paths share no
        // stretch, only a point, and a crossing needs no fork.
        List<Node> street = streetNodes();
        Route a = route(street.toArray(new Node[0]));
        Route b = route(new Node(48.00295, 12.9995), new Node(48.00295, 13.0),
                new Node(48.00295, 13.0005));

        RouteGraph graph = new GraphBuilder(TOLERANCE).simplify(buildWith(List.of(a, b), List.of()));

        assertEquals(0, graph.getJunctions().size(), () -> "vertices: " + graph.getNodes());
        assertEquals(2, graph.getEdges().size(), () -> "edges: " + graph.getEdges());
        assertTrue(graph.getEdges().stream().noneMatch(GraphEdge::isShared));
    }

    /** A north-south street 48.0000..48.0060, samples ~11 m apart. */
    private List<Node> streetNodes() {
        List<Node> street = new ArrayList<>();
        for (int i = 0; i <= 60; i++) {
            street.add(new Node(48.0000 + i * 0.0001, 13.0));
        }
        return street;
    }

    /** The same point shifted east: a second recording of the same place. */
    private Node offsetEast(Node node, double meters) {
        double metersPerDegreeLongitude = 111_320.0 * Math.cos(Math.toRadians(node.getLatitude()));
        return new Node(node.getLatitude(), node.getLongitude() + meters / metersPerDegreeLongitude);
    }

    @Test
    void clusterNoiseDoesNotCreateDegenerateEdges() {
        // several adjacent b-points sit inside the same junction tolerance
        Route a = route(
                new Node(48.0, 13.0),
                new Node(48.002, 13.0),
                new Node(48.004, 13.0));
        Route b = route(
                new Node(48.006, 13.0),
                new Node(48.002, 13.0));
        // c crosses b's junction region with two close-by points
        Route c = route(
                new Node(48.002, 13.00005),
                new Node(48.002, 13.00007),
                new Node(48.000, 13.00005));

        RouteGraph graph = build(List.of(a, b, c));

        for (GraphEdge edge : graph.getEdges()) {
            if (edge.getFrom() == edge.getTo()) {
                assertTrue(edge.getLengthMeters() >= 4 * TOLERANCE,
                        "degenerate self-loop edge was not filtered");
            }
        }
    }
}
