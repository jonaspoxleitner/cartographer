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
        turnAroundAtCorridorEnd(nodes, 0);
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
    void crossRouteOppositePassesYieldCorridorEndJunctions() {
        // two recordings of the same street, 3 m apart, walked in opposite
        // directions: neither route has a track point in common, so the
        // street can only become one edge if both are cut at both corridor ends
        List<Node> northbound = new ArrayList<>();
        corridorPass(northbound, 0, true);
        List<Node> southbound = new ArrayList<>();
        corridorPass(southbound, 0.00004, false);

        List<Intersection> intersections = new IntersectionFinder(10)
                .find(List.of(route(northbound), route(southbound)));

        assertCrossRouteCorridorEndJunctions(intersections);
    }

    @Test
    void crossRouteSameDirectionPassesYieldCorridorEndJunctions() {
        // the same street recorded twice in the same direction: the passes
        // meet at their starts and at their ends, not start to end
        List<Node> first = new ArrayList<>();
        corridorPass(first, 0, true);
        List<Node> second = new ArrayList<>();
        corridorPass(second, 0.00004, true);

        List<Intersection> intersections = new IntersectionFinder(10)
                .find(List.of(route(first), route(second)));

        assertCrossRouteCorridorEndJunctions(intersections);
    }

    @Test
    void crossRouteOneWayPassAndOutAndBackInOneRunShareTheStreet() {
        // one route walks the street once, the other walks it out and back in a
        // single run: it starts where it ends, so its two passes over the south
        // end are two visits of one run, and its turnaround is the far end
        List<Node> once = new ArrayList<>();
        corridorPass(once, 0, true);
        List<Node> outAndBack = new ArrayList<>();
        corridorPass(outAndBack, 0.00004, true);
        corridorPass(outAndBack, 0.00004, false);

        List<Intersection> intersections = new IntersectionFinder(10)
                .find(List.of(route(once), route(outAndBack)));

        assertEquals(2, intersections.size(), "a corridor has exactly two ends");
        for (Intersection intersection : intersections) {
            assertEquals(2, intersection.getRoutes().size(), "both routes reach both ends");
            double atStart = intersection.getLocation().distanceTo(CORRIDOR_START);
            double atEnd = intersection.getLocation().distanceTo(CORRIDOR_END);
            assertTrue(Math.min(atStart, atEnd) <= CORRIDOR_END_RADIUS_METERS,
                    "not at a corridor end: " + intersection);
            // the out-and-back route is cut twice at the end it starts and
            // finishes at, and once where it turns around
            int expected = atStart <= CORRIDOR_END_RADIUS_METERS ? 3 : 2;
            assertEquals(expected, intersection.getPoints().size(),
                    "one point per visit at " + intersection);
        }
    }

    @Test
    void outAndBackInsideOneRunCorridorEndsAtItsTurnaround() {
        // the shape the reported pair has: one route walks the street out and
        // back inside a single run, the other walks it out, detours at the far
        // end and comes back only halfway. The shared stretch is the first
        // route's run, so the corridor ends are its start (which is also its
        // finish) and its turnaround — not any of the other route's run ends.
        List<Node> outAndBack = new ArrayList<>();
        corridorPass(outAndBack, 0.00004, true);
        corridorPass(outAndBack, 0.00004, false);
        List<Node> detour = new ArrayList<>();
        corridorPass(detour, 0, true);
        turnAroundAtCorridorEnd(detour, 0);
        corridorPass(detour, 0, false, 50);
        Route single = route(outAndBack);

        List<Intersection> intersections = new IntersectionFinder(10)
                .find(List.of(single, route(detour)));

        assertEquals(2, intersections.size(), "a corridor has exactly two ends");
        for (Intersection intersection : intersections) {
            assertEquals(3, intersection.getPoints().size(), "one point per visit");
            assertEquals(2, intersection.getRoutes().size(), "both routes reach both ends");
            double atStart = intersection.getLocation().distanceTo(CORRIDOR_START);
            double atEnd = intersection.getLocation().distanceTo(CORRIDOR_END);
            assertTrue(Math.min(atStart, atEnd) <= CORRIDOR_END_RADIUS_METERS,
                    "not at a corridor end: " + intersection);
        }
        Intersection turnaround = intersections.stream()
                .filter(i -> i.getLocation().distanceTo(CORRIDOR_END) <= CORRIDOR_END_RADIUS_METERS)
                .findFirst().orElseThrow();
        assertTrue(turnaround.getPoints().stream()
                        .anyMatch(point -> point.route() == single
                                && point.index() > 0
                                && point.index() < single.getNodes().size() - 1),
                "the far end is the round trip's interior turnaround, not one of its run ends");
    }

    @Test
    void crossRouteCoveragePatternIsNoCorridor() {
        // three routes over the same street: a coverage pattern with its own
        // crossings. Runs cannot be paired into one corridor, so the oversized
        // cluster stays rejected.
        List<Node> first = new ArrayList<>();
        corridorPass(first, 0, true);
        List<Node> second = new ArrayList<>();
        corridorPass(second, 0.00004, true);
        List<Node> third = new ArrayList<>();
        corridorPass(third, 0.00008, false);

        assertTrue(new IntersectionFinder(10)
                        .find(List.of(route(first), route(second), route(third))).isEmpty(),
                "Three passes are a coverage pattern, not one corridor");
    }

    @Test
    void crossRouteOutAndBackWithSinglePassYieldsCorridorEndJunctions() {
        // one route walks the street once, the other walks it once in each
        // direction: three runs, split 1 + 2 by route. The single pass is the
        // shared stretch, and the out-and-back route reaches both of its ends.
        List<Intersection> intersections = new IntersectionFinder(10)
                .find(singlePassAndOutAndBack());

        assertEquals(2, intersections.size(), "a corridor has exactly two ends");
        for (Intersection intersection : intersections) {
            assertEquals(3, intersection.getPoints().size(),
                    "one point per run: the single pass plus both passes of the out-and-back route");
            assertEquals(2, intersection.getRoutes().size(), "both routes reach both ends");
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

    @Test
    void corridorEndIsCutForEveryVisitOfTheOutAndBackRoute() {
        // the out-and-back route passes each corridor end twice: once on the
        // way out and once on the way back. Both visits must become cuts at
        // that end, otherwise the return pass stays a parallel edge of its own.
        List<Route> routes = singlePassAndOutAndBack();
        Route doubled = routes.get(1);

        List<Intersection> intersections = new IntersectionFinder(10).find(routes);

        assertEquals(2, intersections.size(), "a corridor has exactly two ends");
        for (Intersection intersection : intersections) {
            List<RoutePoint> visits = intersection.getPoints().stream()
                    .filter(point -> point.route() == doubled)
                    .toList();
            assertEquals(2, visits.size(), "both passes of the out-and-back route at this end");
            assertNotEquals(visits.get(0).index(), visits.get(1).index(),
                    "two cuts at the same end, one per pass");
            assertTrue(visits.get(0).node().distanceTo(visits.get(1).node()) <= 20,
                    "both visits are at the same corridor end");
        }
    }

    @Test
    void corridorRejectsARouteThatMissesOneEnd() {
        // one route walks the street once, the other only its southern part, so
        // its points come within reach of the south end but never of the north
        // end. The clustering cannot produce such a cluster — every point it
        // clusters is within tolerance of the other route, which is narrower
        // than the corridor's reach — so the corridor rule gets the cluster
        // directly.
        Route full = route(corridorNodes(1, 100, 0));
        Route southOnly = route(corridorNodes(1, 40, 0.00004));
        Route wholeStreet = route(corridorNodes(1, 100, 0.00004));

        assertTrue(new IntersectionFinder(10).splitCorridor(cluster(full, southOnly)).isEmpty(),
                "A pass that misses one corridor end leaves the cluster no corridor");
        assertEquals(2, new IntersectionFinder(10).splitCorridor(cluster(full, wholeStreet)).size(),
                "the same cluster is a corridor once the other route reaches both ends");
    }

    private List<RoutePoint> cluster(Route... routes) {
        List<RoutePoint> points = new ArrayList<>();
        for (Route route : routes) {
            for (int i = 0; i < route.getNodes().size(); i++) {
                points.add(new RoutePoint(route, i, route.getNodes().get(i)));
            }
        }
        return points;
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

    private void assertCrossRouteCorridorEndJunctions(List<Intersection> intersections) {
        assertEquals(2, intersections.size(), "a corridor has exactly two ends");
        for (Intersection intersection : intersections) {
            assertEquals(2, intersection.getPoints().size(), "one point per pass at each end");
            assertEquals(2, intersection.getRoutes().size(), "both routes reach both ends");
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

    /** One route walks the corridor once, the other out and back. */
    private List<Route> singlePassAndOutAndBack() {
        List<Node> once = new ArrayList<>();
        corridorPass(once, 0, true);
        List<Node> outAndBack = new ArrayList<>();
        corridorPass(outAndBack, 0.00004, true);
        turnAroundAtCorridorEnd(outAndBack, 0.00004);
        corridorPass(outAndBack, 0.00004, false);
        return List.of(route(once), route(outAndBack));
    }

    /** The corridor's points {@code from}..{@code to} metres north of its start. */
    private List<Node> corridorNodes(int from, int to, double lonOffset) {
        List<Node> nodes = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            nodes.add(new Node(48.6000 + i * 0.000009, 13.7025 + lonOffset));
        }
        return nodes;
    }

    /** One pass over the corridor, {@code lonOffset} degrees east of it. */
    private void corridorPass(List<Node> nodes, double lonOffset, boolean northbound) {
        corridorPass(nodes, lonOffset, northbound, 100);
    }

    /** The part of that pass, {@code steps} metres from its start. */
    private void corridorPass(List<Node> nodes, double lonOffset, boolean northbound, int steps) {
        for (int step = 1; step <= steps; step++) {
            int i = northbound ? step : 101 - step;
            nodes.add(new Node(48.6000 + i * 0.000009, 13.7025 + lonOffset));
        }
    }

    private Route route(List<Node> nodes) {
        Route route = new Route();
        nodes.forEach(route::addNode);
        return route;
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

    /** At the corridor's end, {@code lonOffset} degrees east of it: a ~60 m
     *  excursion east and back, off the corridor. */
    private void turnAroundAtCorridorEnd(List<Node> nodes, double lonOffset) {
        for (int i = 1; i <= 60; i++) {
            nodes.add(new Node(48.6009, 13.7025 + lonOffset + i * 0.0000135));
        }
        for (int i = 59; i >= 0; i--) {
            nodes.add(new Node(48.6009, 13.7025 + lonOffset + i * 0.0000135));
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
