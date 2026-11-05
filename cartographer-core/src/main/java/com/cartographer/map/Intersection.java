package com.cartographer.map;

import com.cartographer.model.Node;
import com.cartographer.model.Route;

import java.util.List;

/**
 * A point where paths meet: a cluster of track nodes lying within the
 * matching tolerance. Usually spans several routes; a single-route
 * cluster is a self-intersection (a loop crossing or retracing itself).
 * These are the candidate junctions for switching paths when pathfinding.
 */
public class Intersection {

    private final Node location;
    private final List<RoutePoint> points;

    public Intersection(Node location, List<RoutePoint> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("An intersection requires at least 2 points");
        }
        this.location = location;
        this.points = List.copyOf(points);
    }

    /** Representative point for the cluster (average of its nodes). */
    public Node getLocation() {
        return location;
    }

    /** All route points clustered at this intersection. */
    public List<RoutePoint> getPoints() {
        return points;
    }

    /** The distinct routes meeting at this intersection. */
    public List<Route> getRoutes() {
        return points.stream().map(RoutePoint::route).distinct().toList();
    }

    @Override
    public String toString() {
        return "Intersection{lat=" + location.getLatitude()
                + ", lon=" + location.getLongitude()
                + ", routes=" + getRoutes().size()
                + ", points=" + points.size()
                + '}';
    }
}
