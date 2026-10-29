package com.cartographer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named route consisting of an ordered path of track nodes
 * and a set of named waypoints (points of interest).
 */
public class Route {

    private String name;
    private final List<Node> nodes = new ArrayList<>();
    private final List<Node> waypoints = new ArrayList<>();

    public Route() {
    }

    public Route(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void addNode(Node node) {
        nodes.add(node);
    }

    public void addWaypoint(Node waypoint) {
        waypoints.add(waypoint);
    }

    /**
     * The ordered track nodes forming the path (modifiable so the
     * map/graph builder can snap or merge nodes later).
     */
    public List<Node> getNodes() {
        return nodes;
    }

    public List<Node> getWaypoints() {
        return Collections.unmodifiableList(waypoints);
    }

    /** Total length of the path in meters. */
    public double totalDistanceMeters() {
        double total = 0;
        for (int i = 1; i < nodes.size(); i++) {
            total += nodes.get(i - 1).distanceTo(nodes.get(i));
        }
        return total;
    }

    @Override
    public String toString() {
        return "Route{name='" + name + '\''
                + ", nodes=" + nodes.size()
                + ", waypoints=" + waypoints.size()
                + '}';
    }
}
