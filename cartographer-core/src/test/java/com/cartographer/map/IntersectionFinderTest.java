package com.cartographer.map;

import com.cartographer.model.Node;
import com.cartographer.model.Route;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntersectionFinderTest {

    private Route route(Node... nodes) {
        Route route = new Route();
        for (Node node : nodes) {
            route.addNode(node);
        }
        return route;
    }

    @Test
    void findsSharedPointBetweenRoutes() {
        Route a = route(new Node(48.0, 13.0), new Node(48.001, 13.001));
        Route b = route(new Node(48.0, 13.0), new Node(47.999, 12.999));

        List<Intersection> intersections = new IntersectionFinder(10).find(List.of(a, b));

        assertEquals(1, intersections.size());
        assertEquals(2, intersections.get(0).getPoints().size());
        assertEquals(List.of(a, b), intersections.get(0).getRoutes());
    }

    @Test
    void pointsBeyondToleranceDoNotIntersect() {
        Route a = route(new Node(48.0, 13.0));
        // ~11 m of latitude
        Route b = route(new Node(48.0001, 13.0));

        assertTrue(new IntersectionFinder(10).find(List.of(a, b)).isEmpty());
        assertEquals(1, new IntersectionFinder(15).find(List.of(a, b)).size());
    }

    @Test
    void ignoresSelfIntersections() {
        Route loop = route(new Node(48.0, 13.0), new Node(48.001, 13.001), new Node(48.0, 13.0));
        Route other = route(new Node(47.0, 12.0));

        assertTrue(new IntersectionFinder(10).find(List.of(loop, other)).isEmpty());
    }

    @Test
    void findsSelfIntersectionWhenEnabled() {
        // out-and-back: route returns along the same street after a trip
        List<Node> nodes = new ArrayList<>(List.of(
                new Node(48.0000, 13.0),
                new Node(48.0010, 13.0),   // street point A, outward
                new Node(48.0020, 13.0),
                new Node(48.0030, 13.0)));
        // filler nodes far away so indices are far apart
        for (int i = 0; i < 6; i++) {
            nodes.add(new Node(48.0042 + i * 0.0001, 13.003));
        }
        nodes.add(new Node(48.0030, 13.0001));
        nodes.add(new Node(48.0010, 13.0001)); // street point A, return
        nodes.add(new Node(48.0001, 13.0001));
        Route loop = new Route();
        nodes.forEach(loop::addNode);

        assertTrue(new IntersectionFinder(10).find(List.of(loop)).isEmpty(),
                "without self-intersections enabled: nothing");
        List<Intersection> self = new IntersectionFinder(10).findWithSelfIntersections(List.of(loop));
        assertFalse(self.isEmpty());
        assertEquals(1, self.stream().map(i -> i.getRoutes().size()).distinct().count());
    }

    @Test
    void findsEastWestClosePointsAcrossGridCells() {
        // 9 m apart almost purely east-west at lat 48: more than one grid
        // cell of longitude, because a longitude degree shrinks with latitude
        Route a = route(new Node(48.600756, 13.790176));
        Route b = route(new Node(48.600772, 13.790302));

        assertEquals(1, new IntersectionFinder(10).find(List.of(a, b)).size());
    }

    @Test
    void clustersChainedNearbyPoints() {
        Route a = route(new Node(48.0, 13.0), new Node(48.0, 13.00008));
        Route b = route(new Node(48.0, 13.00004), new Node(48.0, 13.0001));

        List<Intersection> intersections = new IntersectionFinder(8).find(List.of(a, b));

        assertEquals(1, intersections.size());
        assertEquals(4, intersections.get(0).getPoints().size());
    }

    @Test
    void stationaryDensePointsDoNotSelfIntersect() {
        // a time-based recording while standing still: every point within a
        // few meters of the previous one, far more than 8 points back to back
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            nodes.add(new Node(48.0 + (i % 3) * 0.00002, 13.0 + (i % 3) * 0.00003));
        }
        Route walk = new Route();
        nodes.forEach(walk::addNode);

        assertTrue(new IntersectionFinder(10).findWithSelfIntersections(List.of(walk)).isEmpty(),
                "Lingering in one place is not a crossroads");
    }

    @Test
    void denseOutAndBackYieldsCorridorEndJunctions() {
        // an out-and-back walk along one street, sampled every ~1 m: the two
        // legs stay within tolerance of each other along the whole corridor,
        // so they chain into one street-length cluster. That cluster is cut
        // at the corridor's ends, not dropped: the corridor is a crossroads
        // where the walk turns around and where it joins the spurs.
        List<Node> nodes = new ArrayList<>();
        spurToStart(nodes);
        corridorNorth(nodes);
        turnAroundAtEnd(nodes);
        for (int i = 1; i <= 100; i++) {
            nodes.add(new Node(48.6009 - i * 0.000009, 13.70255)); // corridor back T -> S
        }
        spurFromStart(nodes);
        Route walk = new Route();
        nodes.forEach(walk::addNode);

        List<Intersection> intersections = new IntersectionFinder(10).findWithSelfIntersections(List.of(walk));

        assertCorridorEndJunctions(intersections);
    }

    @Test
    void sameDirectionDoublePassYieldsCorridorEndJunctions() {
        // the street walked twice the same way, leaving it to a parallel
        // street 60 m east in between: the two passes meet at both ends
        List<Node> nodes = new ArrayList<>();
        corridorNorth(nodes);
        backToStartOffCorridor(nodes);
        corridorNorth(nodes);
        Route walk = new Route();
        nodes.forEach(walk::addNode);

        List<Intersection> intersections = new IntersectionFinder(10).findWithSelfIntersections(List.of(walk));

        assertCorridorEndJunctions(intersections);
    }

    @Test
    void lawnPatternIsNotACorridor() {
        // lawn mowing: three passes over the same corridor, each one entered
        // from a parallel street. Runs cannot be paired into one corridor, so
        // the oversized cluster stays rejected and the track renders as walked.
        List<Node> nodes = new ArrayList<>();
        for (int pass = 0; pass < 3; pass++) {
            corridorNorth(nodes);
            backToStartOffCorridor(nodes);
        }
        Route walk = new Route();
        nodes.forEach(walk::addNode);

        assertTrue(new IntersectionFinder(10).findWithSelfIntersections(List.of(walk)).isEmpty(),
                "Three passes are a coverage pattern, not an out-and-back corridor");
    }

    @Test
    void findsDenseSelfIntersectionWhenReallyCrossing() {
        // lollipop: north, east, south, then back west across the first leg,
        // every step ~1 m. The crossing is point-sized with a real detour.
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i <= 66; i++) { // north: (48.0, 13.0) -> (48.0006, 13.0)
            nodes.add(new Node(48.0 + i * 0.000009, 13.0));
        }
        for (int i = 1; i <= 60; i++) { // east -> (48.0006, 13.00081)
            nodes.add(new Node(48.0006, 13.0 + i * 0.0000135));
        }
        for (int i = 1; i <= 33; i++) { // south -> (48.0003, 13.00081)
            nodes.add(new Node(48.0006 - i * 0.000009, 13.00081));
        }
        for (int i = 1; i <= 68; i++) { // west -> (48.0003, 12.99989), crossing the north leg
            nodes.add(new Node(48.0003, 13.00081 - i * 0.0000135));
        }
        Route walk = new Route();
        nodes.forEach(walk::addNode);

        assertFalse(new IntersectionFinder(10).findWithSelfIntersections(List.of(walk)).isEmpty(),
                "A densely sampled self-crossing is an intersection");
    }

    @Test
    void intersectionRequiresTwoRoutes() {
        Route a = route(new Node(48.0, 13.0));

        assertThrows(IllegalArgumentException.class,
                () -> new Intersection(new Node(48.0, 13.0),
                        List.of(new RoutePoint(a, 0, a.getNodes().get(0)))));
    }

    // Corridor S->T is 100 m long at ~1 m per step (0.000009 deg latitude,
    // 0.0000135 deg longitude at this latitude).
    private static final Node CORRIDOR_START = new Node(48.6000, 13.7025);
    private static final Node CORRIDOR_END = new Node(48.6009, 13.7025);
    private static final double CORRIDOR_END_RADIUS_METERS = 40;

    private void assertCorridorEndJunctions(List<Intersection> intersections) {
        assertEquals(2, intersections.size(), "a corridor has exactly two ends");
        for (Intersection intersection : intersections) {
            assertEquals(2, intersection.getPoints().size(), "one point per leg at each end");
            assertEquals(1, intersection.getRoutes().size());
            double atStart = intersection.getLocation().distanceTo(CORRIDOR_START);
            double atEnd = intersection.getLocation().distanceTo(CORRIDOR_END);
            assertTrue(Math.min(atStart, atEnd) <= CORRIDOR_END_RADIUS_METERS,
                    "not at a corridor end: " + intersection);
        }
        assertTrue(intersections.stream()
                        .anyMatch(i -> i.getLocation().distanceTo(CORRIDOR_START) <= CORRIDOR_END_RADIUS_METERS),
                "one junction at the corridor start");
        assertTrue(intersections.stream()
                        .anyMatch(i -> i.getLocation().distanceTo(CORRIDOR_END) <= CORRIDOR_END_RADIUS_METERS),
                "one junction at the corridor end");
    }

    private void corridorNorth(List<Node> nodes) {
        for (int i = 1; i <= 100; i++) {
            nodes.add(new Node(48.6000 + i * 0.000009, 13.7025));
        }
    }

    /** A ~185 m spur from K to the corridor's start. */
    private void spurToStart(List<Node> nodes) {
        for (int i = 0; i <= 185; i++) {
            nodes.add(new Node(48.6000, 13.7000 + i * 0.0000135));
        }
    }

    /** A ~185 m spur from the corridor's start to L. */
    private void spurFromStart(List<Node> nodes) {
        for (int i = 1; i <= 185; i++) {
            nodes.add(new Node(48.6000, 13.7025 + i * 0.0000135));
        }
    }

    /** At the corridor's end: a ~60 m excursion east and back, off the corridor. */
    private void turnAroundAtEnd(List<Node> nodes) {
        for (int i = 1; i <= 60; i++) {
            nodes.add(new Node(48.6009, 13.7025 + i * 0.0000135));
        }
        for (int i = 59; i >= 0; i--) {
            nodes.add(new Node(48.6009, 13.7025 + i * 0.0000135));
        }
    }

    /** East away from the corridor's end, south 60 m to its side, back west to its start. */
    private void backToStartOffCorridor(List<Node> nodes) {
        for (int i = 1; i <= 60; i++) {
            nodes.add(new Node(48.6009, 13.7025 + i * 0.0000135));
        }
        for (int i = 1; i <= 100; i++) {
            nodes.add(new Node(48.6009 - i * 0.000009, 13.70331));
        }
        for (int i = 1; i <= 60; i++) {
            nodes.add(new Node(48.6000, 13.70331 - i * 0.0000135));
        }
    }
}
