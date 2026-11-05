package com.cartographer.map;

import com.cartographer.model.Node;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A vertex of the route graph: either a junction where routes meet
 * or an endpoint (route start/finish).
 */
public class GraphNode {

    private static final AtomicInteger ID_SOURCE = new AtomicInteger();

    private final int id = ID_SOURCE.getAndIncrement();
    private final Node location;
    private final boolean junction;

    public GraphNode(Node location, boolean junction) {
        this.location = location;
        this.junction = junction;
    }

    public int getId() {
        return id;
    }

    public Node getLocation() {
        return location;
    }

    public boolean isJunction() {
        return junction;
    }

    @Override
    public String toString() {
        return "GraphNode{#" + id
                + ", lat=" + location.getLatitude()
                + ", lon=" + location.getLongitude()
                + (junction ? ", junction" : "")
                + '}';
    }
}
