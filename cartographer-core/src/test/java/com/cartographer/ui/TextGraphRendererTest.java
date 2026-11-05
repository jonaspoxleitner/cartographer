package com.cartographer.ui;

import com.cartographer.map.GraphBuilder;
import com.cartographer.map.IntersectionFinder;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;
import com.cartographer.model.Route;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextGraphRendererTest {

    private Route route(Node... nodes) {
        Route route = new Route();
        for (Node node : nodes) {
            route.addNode(node);
        }
        return route;
    }

    private RouteGraph build(Route... routes) {
        List<Route> list = List.of(routes);
        return new GraphBuilder(10).build(list, new IntersectionFinder(10).find(list));
    }

    @Test
    void labelsVerticesAndAdjacency() {
        Route a = route(new Node(48.0, 13.0), new Node(48.001, 13.0), new Node(48.002, 13.0));
        Route b = route(new Node(48.001, 13.0), new Node(48.0005, 13.001));

        String text = new TextGraphRenderer().render(build(a, b));

        assertTrue(text.contains("A*"), "junction A missing");
        assertTrue(text.contains("A -> "), "adjacency line for A missing");
        assertTrue(text.contains("Vertices"));
        // junction connects to both of a's endpoints and b's endpoint; each
        // neighbor is printed as "label (distance)", so count parentheses
        String adjacencyA = adjacency(text).lines()
                .filter(l -> l.startsWith("  A -> "))
                .findFirst().orElseThrow();
        long neighbors = adjacencyA.chars().filter(c -> c == '(').count();
        assertEquals(3, neighbors, () -> "expected 3 neighbors for junction A: " + adjacencyA);
    }

    @Test
    void labelSequenceRollsOverAfterZ() {
        assertEquals("A", TextGraphRenderer.label(0));
        assertEquals("Z", TextGraphRenderer.label(25));
        assertEquals("AA", TextGraphRenderer.label(26));
        assertEquals("AB", TextGraphRenderer.label(27));
        assertEquals("BA", TextGraphRenderer.label(52));
    }

    private static String adjacency(String text) {
        return text.split("Adjacency")[1];
    }

    @Test
    void sectionsListEachEdgeOnce() {
        Route a = route(new Node(48.0, 13.0), new Node(48.001, 13.0));
        Route b = route(new Node(48.0, 13.0), new Node(48.001, 13.0));

        String text = new TextGraphRenderer().render(build(a, b));

        String sections = text.split("Sections")[1].split("Adjacency")[0];
        List<String> lines = sections.lines().filter(l -> l.contains("->")).toList();
        // two identical routes merge into one shared section between two vertices
        assertEquals(1, lines.size(), () -> "sections printed: " + sections);
        assertTrue(lines.get(0).contains("*"), "shared section marker missing: " + lines.get(0));
        assertTrue(lines.get(0).contains("m)"), "length missing: " + lines.get(0));
    }

    @Test
    void longSectionsUseKilometers() {
        Route a = route(new Node(48.0, 13.0), new Node(48.1, 13.0)); // ~11 km

        String text = new TextGraphRenderer().render(build(a));

        assertTrue(text.contains("km)"), () -> "expected km formatting in: " + text);
    }

    @Test
    void sharedEdgesAreMarked() {
        Route a = route(new Node(48.0, 13.0), new Node(48.001, 13.0));
        Route b = route(new Node(48.0, 13.0), new Node(48.001, 13.0));

        String text = new TextGraphRenderer().render(build(a, b));

        long sharedMarks = text.lines().filter(l -> l.contains("*(") || l.matches(".*[A-Z]\\* \\(.*")).count();
        assertTrue(sharedMarks >= 2, "expected shared edge markers in adjacency: " + text);
    }
}
