package com.cartographer.server.api;

import com.cartographer.map.GraphEdge;
import com.cartographer.map.GraphNode;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;
import com.cartographer.model.Route;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link RouteGraph} into a GeoJSON FeatureCollection: one LineString
 * feature per edge, carrying how often that stretch is traveled, and one Point
 * feature per vertex. A vertex Point sits at the same location the edge paths
 * end at, so the page can draw its dots from the graph rather than from the
 * path geometry. Positions are written in GeoJSON order,
 * [longitude, latitude].
 */
@Component
public class GeoJsonMapper {

    public FeatureCollection toFeatureCollection(RouteGraph graph) {
        List<Feature> features = new ArrayList<>(graph.getEdges().size() + graph.getNodes().size());
        for (GraphEdge edge : graph.getEdges()) {
            features.add(edgeFeature(edge));
        }
        for (GraphNode vertex : graph.getNodes()) {
            features.add(vertexFeature(vertex));
        }
        return new FeatureCollection("FeatureCollection", List.copyOf(features));
    }

    private static Feature edgeFeature(GraphEdge edge) {
        List<List<Double>> coordinates = edge.getPath().stream().map(GeoJsonMapper::position).toList();
        EdgeProperties properties = new EdgeProperties(edge.getTraversals(), edge.getLengthMeters(),
                edge.isShared(), routeNames(edge));
        return new Feature("Feature", new LineStringGeometry(coordinates), properties);
    }

    private static Feature vertexFeature(GraphNode vertex) {
        return new Feature("Feature", new PointGeometry(position(vertex.getLocation())),
                new VertexProperties(vertex.isJunction()));
    }

    private static List<Double> position(Node node) {
        return List.of(node.getLongitude(), node.getLatitude());
    }

    private static List<String> routeNames(GraphEdge edge) {
        return edge.getRoutes().stream().map(Route::getName).toList();
    }

    public record FeatureCollection(String type, List<Feature> features) {
    }

    /** One feature: its geometry and the properties of that geometry. */
    public record Feature(String type, Geometry geometry, FeatureProperties properties) {
    }

    /** GeoJSON geometry: a path of positions or a single position. */
    public sealed interface Geometry permits LineStringGeometry, PointGeometry {
    }

    /** A path of positions, [longitude, latitude] each. */
    public record LineStringGeometry(String type, List<List<Double>> coordinates) implements Geometry {

        public LineStringGeometry(List<List<Double>> coordinates) {
            this("LineString", coordinates);
        }
    }

    /** One position, [longitude, latitude]. */
    public record PointGeometry(String type, List<Double> coordinates) implements Geometry {

        public PointGeometry(List<Double> coordinates) {
            this("Point", coordinates);
        }
    }

    /** What a feature's properties describe. */
    public sealed interface FeatureProperties permits EdgeProperties, VertexProperties {
    }

    public record EdgeProperties(int traversals, double lengthMeters, boolean shared, List<String> routes)
            implements FeatureProperties {
    }

    /** Whether the vertex is a junction or a route start/finish. */
    public record VertexProperties(boolean junction) implements FeatureProperties {
    }
}
