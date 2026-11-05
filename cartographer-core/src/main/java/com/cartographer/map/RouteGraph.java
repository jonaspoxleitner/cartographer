package com.cartographer.map;

import com.cartographer.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The merged route map: vertices (junctions and endpoints) connected
 * by undirected edges. This is the structure pathfinding operates on.
 */
public class RouteGraph {

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Map<GraphNode, List<GraphEdge>> adjacency = new HashMap<>();

    public GraphNode addJunction(Node location) {
        return addNode(location, true);
    }

    public GraphNode addEndpoint(Node location) {
        return addNode(location, false);
    }

    private GraphNode addNode(Node location, boolean junction) {
        GraphNode node = new GraphNode(location, junction);
        nodes.add(node);
        adjacency.put(node, new ArrayList<>());
        return node;
    }

    /** Re-registers an existing vertex — used when rebuilding simplified graphs. */
    public void addExisting(GraphNode node) {
        nodes.add(node);
        adjacency.putIfAbsent(node, new ArrayList<>());
    }

    public void addEdge(GraphEdge edge) {
        edges.add(edge);
        adjacency.get(edge.getFrom()).add(edge);
        if (edge.getTo() != edge.getFrom()) {
            adjacency.get(edge.getTo()).add(edge);
        }
    }

    public List<GraphNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<GraphEdge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    /** All edges incident to a node — the traversal frontier for pathfinding. */
    public List<GraphEdge> edgesFrom(GraphNode node) {
        return adjacency.getOrDefault(node, List.of());
    }

    public List<GraphNode> getJunctions() {
        return nodes.stream().filter(GraphNode::isJunction).toList();
    }

    public double totalLengthMeters() {
        return edges.stream().mapToDouble(GraphEdge::getLengthMeters).sum();
    }
}
