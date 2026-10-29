package com.cartographer.ui;

import com.cartographer.map.GraphBuilder;
import com.cartographer.map.IntersectionFinder;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;
import com.cartographer.model.Route;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SvgGraphRendererTest {

    private Route route(Node... nodes) {
        Route route = new Route();
        for (Node node : nodes) {
            route.addNode(node);
        }
        return route;
    }

    @Test
    void rendersValidSvgStructure() {
        // a and b share the stretch (48.001)-(48.002), so one edge is shared
        Route a = route(new Node(48.0, 13.0), new Node(48.001, 13.0), new Node(48.002, 13.0));
        Route b = route(new Node(48.004, 13.0), new Node(48.002, 13.0), new Node(48.001, 13.0));
        List<Route> routes = List.of(a, b);
        RouteGraph graph = new GraphBuilder(10).build(routes, new IntersectionFinder(10).find(routes));

        String svg = new SvgGraphRenderer().render(graph);

        assertTrue(svg.startsWith("<svg"), "missing root element");
        assertTrue(svg.trim().endsWith("</svg>"), "missing closing tag");
        // one polyline per edge
        assertEquals(graph.getEdges().size(), count(svg, "<polyline"));
        // junction (radius 9) + two endpoints
        assertEquals(graph.getNodes().size(), count(svg, "<circle"));
        // junction label present
        assertTrue(svg.contains(">A</text>"), "junction label missing");
        // the shared color appears for the shared edge
        assertTrue(svg.contains("#f0a500"), "shared edge styling missing");
    }

    @Test
    void projectionKeepsNorthUp() {
        Route a = route(new Node(48.0, 13.0), new Node(48.005, 13.0));
        RouteGraph graph = new GraphBuilder(10).build(List.of(a), List.of());

        String svg = new SvgGraphRenderer().render(graph);

        // extract the two y coordinates of the single polyline
        String points = svg.split("points=\"")[1].split("\"")[0];
        double ySouth = Double.parseDouble(points.trim().split(" ")[0].split(",")[1]);
        double yNorth = Double.parseDouble(points.trim().split(" ")[1].split(",")[1]);
        assertTrue(yNorth < ySouth, "northern point must have smaller y (north up)");
    }

    private int count(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
