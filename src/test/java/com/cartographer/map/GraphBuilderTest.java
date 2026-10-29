package com.cartographer.map;

import com.cartographer.model.Node;
import com.cartographer.model.Route;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphBuilderTest {

    private static final double TOLERANCE = 10.0;
    // 0.001 deg latitude ~ 111 m

    private Route route(Node... nodes) {
        Route route = new Route();
        for (Node node : nodes) {
            route.addNode(node);
        }
        return route;
    }

    private RouteGraph build(List<Route> routes) {
        List<Intersection> intersections = new IntersectionFinder(TOLERANCE).find(routes);
        return new GraphBuilder(TOLERANCE).build(routes, intersections);
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
