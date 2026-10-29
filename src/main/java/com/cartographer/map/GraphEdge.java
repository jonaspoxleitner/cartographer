package com.cartographer.map;

import com.cartographer.model.Node;
import com.cartographer.model.Route;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An undirected edge of the route graph: a stretch of path between
 * two vertices. Edges shared by multiple routes (overlapping
 * segments) are merged into one and carry all contributing routes.
 */
public class GraphEdge {

    private final GraphNode from;
    private final GraphNode to;
    private final List<Node> path;
    private final double lengthMeters;
    private final Set<Route> routes = new LinkedHashSet<>();
    private int traversals = 1;

    public GraphEdge(GraphNode from, GraphNode to, List<Node> path, Route route) {
        this.from = from;
        this.to = to;
        this.path = List.copyOf(path);
        this.routes.add(route);
        double length = 0;
        for (int i = 1; i < path.size(); i++) {
            length += path.get(i - 1).distanceTo(path.get(i));
        }
        this.lengthMeters = length;
    }

    public GraphNode getFrom() {
        return from;
    }

    public GraphNode getTo() {
        return to;
    }

    /** The polyline of track nodes between the two endpoints. */
    public List<Node> getPath() {
        return path;
    }

    public double getLengthMeters() {
        return lengthMeters;
    }

    public Set<Route> getRoutes() {
        return routes;
    }

    void addRoute(Route route) {
        routes.add(route);
        traversals++;
    }

    void setTraversals(int traversals) {
        this.traversals = traversals;
    }

    public int getTraversals() {
        return traversals;
    }

    /** True when this edge is traveled more than once: shared by several
     *  routes, or retraced by a single route (out-and-back street). */
    public boolean isShared() {
        return traversals > 1;
    }

    /** The vertex on the other end of this edge. */
    public GraphNode otherEnd(GraphNode node) {
        if (node == from) {
            return to;
        }
        if (node == to) {
            return from;
        }
        throw new IllegalArgumentException("Node is not an endpoint of this edge");
    }

    @Override
    public String toString() {
        return "GraphEdge{#" + from.getId() + " <-> #" + to.getId()
                + String.format(", %.0f m", lengthMeters)
                + ", routes=" + routes.size()
                + '}';
    }
}
