package com.cartographer.ui;

import com.cartographer.map.GraphEdge;
import com.cartographer.map.GraphNode;
import com.cartographer.map.RouteGraph;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a route graph as plain text: each vertex gets a label
 * (A, B, ...), then a compact, deduplicated list of sections
 * (from one junction/start to the next), and finally a full
 * adjacency list. Shared sections are marked '*'.
 */
public class TextGraphRenderer {

    public String render(RouteGraph graph) {
        List<GraphNode> vertices = ordered(graph.getNodes());
        Map<GraphNode, String> labels = new IdentityHashMap<>();
        for (int i = 0; i < vertices.size(); i++) {
            labels.put(vertices.get(i), label(i));
        }

        StringBuilder out = new StringBuilder();
        out.append("Vertices (* = junction):\n");
        for (GraphNode vertex : vertices) {
            out.append(String.format("  %s%s  (%.6f, %.6f)%n",
                    labels.get(vertex),
                    vertex.isJunction() ? "*" : " ",
                    vertex.getLocation().getLatitude(),
                    vertex.getLocation().getLongitude()));
        }

        out.append('\n');
        out.append("Sections (* = shared, from one junction/start to the next):\n");
        for (GraphEdge edge : ordered(graph.getEdges(), labels)) {
            out.append(String.format("  %s -> %s%s (%s)\n",
                    labels.get(edge.getFrom()),
                    labels.get(edge.getTo()),
                    edge.isShared() ? "*" : "",
                    formatLength(edge.getLengthMeters())));
        }
        out.append('\n');
        out.append("Adjacency (* = shared edge):\n");
        for (GraphNode vertex : vertices) {
            out.append("  ").append(labels.get(vertex)).append(" -> ");
            List<GraphEdge> edges = graph.edgesFrom(vertex).stream()
                    .sorted(Comparator.comparing(e -> labels.get(e.otherEnd(vertex))))
                    .toList();
            if (edges.isEmpty()) {
                out.append("(isolated)");
            }
            for (int i = 0; i < edges.size(); i++) {
                GraphEdge edge = edges.get(i);
                if (i > 0) {
                    out.append(", ");
                }
                out.append(labels.get(edge.otherEnd(vertex)));
                if (edge.isShared()) {
                    out.append('*');
                }
                out.append(String.format(" (%s)", formatLength(edge.getLengthMeters())));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Deterministic order: junctions first, then endpoints. */
    static List<GraphNode> ordered(List<GraphNode> vertices) {
        return vertices.stream()
                .sorted(Comparator.comparing(GraphNode::isJunction).reversed()
                        .thenComparing(GraphNode::getId))
                .toList();
    }

    /** Edges in label order of their endpoints. */
    private List<GraphEdge> ordered(List<GraphEdge> edges, Map<GraphNode, String> labels) {
        return edges.stream()
                .sorted(Comparator.comparing((GraphEdge e) -> labels.get(e.getFrom()))
                        .thenComparing(e -> labels.get(e.getTo())))
                .toList();
    }

    private static String formatLength(double meters) {
        return meters < 1000
                ? String.format("%.0f m", meters)
                : String.format("%.2f km", meters / 1000.0);
    }

    /** A..Z, then AA, AB, ... */
    static String label(int index) {
        StringBuilder label = new StringBuilder();
        do {
            label.insert(0, (char) ('A' + index % 26));
            index = index / 26 - 1;
        } while (index >= 0);
        return label.toString();
    }
}
