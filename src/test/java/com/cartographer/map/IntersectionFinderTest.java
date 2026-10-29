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
    void intersectionRequiresTwoRoutes() {
        Route a = route(new Node(48.0, 13.0));

        assertThrows(IllegalArgumentException.class,
                () -> new Intersection(new Node(48.0, 13.0),
                        List.of(new RoutePoint(a, 0, a.getNodes().get(0)))));
    }
}
