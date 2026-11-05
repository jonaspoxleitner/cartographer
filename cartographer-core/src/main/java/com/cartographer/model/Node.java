package com.cartographer.model;

import java.util.Objects;

/**
 * A point on the map, defined by its geographic coordinates.
 * Nodes carry an optional elevation and name (e.g. for waypoints).
 */
public class Node {

    private final double latitude;
    private final double longitude;
    private final Double elevation;
    private final String name;

    public Node(double latitude, double longitude) {
        this(latitude, longitude, null, null);
    }

    public Node(double latitude, double longitude, Double elevation) {
        this(latitude, longitude, elevation, null);
    }

    public Node(double latitude, double longitude, Double elevation, String name) {
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude out of range: " + latitude);
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude out of range: " + longitude);
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.elevation = elevation;
        this.name = name;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public Double getElevation() {
        return elevation;
    }

    public String getName() {
        return name;
    }

    /**
     * Great-circle distance to another node in meters (Haversine formula).
     * Useful as an edge weight / heuristic for pathfinding.
     */
    public double distanceTo(Node other) {
        final double earthRadiusMeters = 6_371_000;
        double lat1 = Math.toRadians(latitude);
        double lat2 = Math.toRadians(other.latitude);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(other.longitude - longitude);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * earthRadiusMeters * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Node node)) {
            return false;
        }
        return Double.compare(node.latitude, latitude) == 0
                && Double.compare(node.longitude, longitude) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(latitude, longitude);
    }

    @Override
    public String toString() {
        return "Node{lat=" + latitude + ", lon=" + longitude
                + (elevation != null ? ", ele=" + elevation : "")
                + (name != null ? ", name='" + name + '\'' : "")
                + '}';
    }
}
