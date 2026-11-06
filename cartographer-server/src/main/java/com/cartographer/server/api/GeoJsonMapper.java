package com.cartographer.server.api;

import com.cartographer.map.GraphEdge;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;
import com.cartographer.model.Route;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns a {@link RouteGraph} into a GeoJSON FeatureCollection: one LineString
 * feature per edge, carrying how often that stretch is traveled. Positions
 * are written in GeoJSON order, [longitude, latitude].
 */
@Component
public class GeoJsonMapper {

    public FeatureCollection toFeatureCollection(RouteGraph graph) {
        List<Feature> features = graph.getEdges().stream().map(this::toFeature).toList();
        return new FeatureCollection("FeatureCollection", features);
    }

    private Feature toFeature(GraphEdge edge) {
        List<List<Double>> coordinates = edge.getPath().stream().map(GeoJsonMapper::position).toList();
        EdgeProperties properties = new EdgeProperties(edge.getTraversals(), edge.getLengthMeters(),
                edge.isShared(), routeNames(edge));
        return new Feature("Feature", new Geometry("LineString", coordinates), properties);
    }

    private static List<Double> position(Node node) {
        return List.of(node.getLongitude(), node.getLatitude());
    }

    private static List<String> routeNames(GraphEdge edge) {
        return edge.getRoutes().stream().map(Route::getName).toList();
    }

    public record FeatureCollection(String type, List<Feature> features) {
    }

    public record Feature(String type, Geometry geometry, EdgeProperties properties) {
    }

    public record Geometry(String type, List<List<Double>> coordinates) {
    }

    public record EdgeProperties(int traversals, double lengthMeters, boolean shared, List<String> routes) {
    }
}
