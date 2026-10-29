package com.cartographer.ui;

import com.cartographer.map.GraphEdge;
import com.cartographer.map.GraphNode;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;

import java.util.List;
import java.util.Locale;

/**
 * Renders the route graph as a self-contained SVG map (no dependencies,
 * opens in any browser). Real geography, north up: shared edges are
 * drawn wider and highlighted, junctions as red labeled dots and
 * endpoints as small gray dots.
 */
public class SvgGraphRenderer {

    private static final double WIDTH = 1000;
    private static final double PADDING = 40;

    private static final String SINGLE_COLOR = "#9e9e9e";
    private static final String SHARED_COLOR = "#f0a500";
    private static final String JUNCTION_COLOR = "#d32f2f";
    private static final String ENDPOINT_COLOR = "#616161";

    public String render(RouteGraph graph) {
        List<GraphNode> vertices = TextGraphRenderer.ordered(graph.getNodes());
        Projection projection = new Projection(graph);

        StringBuilder svg = new StringBuilder();
        svg.append(String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %.0f %.0f\" font-family=\"sans-serif\">%n",
                WIDTH, projection.height()));
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#fafafa\"/>\n");

        for (GraphEdge edge : graph.getEdges()) {
            svg.append(edgeSvg(edge, projection));
        }
        for (int i = 0; i < vertices.size(); i++) {
            svg.append(vertexSvg(vertices.get(i), TextGraphRenderer.label(i), projection));
        }

        svg.append("</svg>\n");
        return svg.toString();
    }

    private String edgeSvg(GraphEdge edge, Projection projection) {
        StringBuilder points = new StringBuilder();
        for (Node node : edge.getPath()) {
            points.append(String.format(Locale.ROOT, "%.1f,%.1f ",
                    projection.x(node), projection.y(node)));
        }
        return String.format(Locale.ROOT,
                "<polyline points=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.0f\" "
                        + "stroke-linejoin=\"round\" stroke-linecap=\"round\"/>%n",
                points.toString().trim(),
                edge.isShared() ? SHARED_COLOR : SINGLE_COLOR,
                edge.isShared() ? 6.0 : 3.0);
    }

    private String vertexSvg(GraphNode vertex, String label, Projection projection) {
        double x = projection.x(vertex.getLocation());
        double y = projection.y(vertex.getLocation());
        if (!vertex.isJunction()) {
            return String.format(Locale.ROOT,
                    "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"4\" fill=\"%s\"/>%n",
                    x, y, ENDPOINT_COLOR);
        }
        return String.format(Locale.ROOT,
                "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"9\" fill=\"%s\" stroke=\"white\" stroke-width=\"2\"/>%n"
                        + "<text x=\"%.1f\" y=\"%.1f\" font-size=\"13\" font-weight=\"bold\" "
                        + "text-anchor=\"middle\" dy=\"-14\" fill=\"#333\">%s</text>%n",
                x, y, JUNCTION_COLOR,
                x, y, label);
    }

    /** Equirectangular projection: longitude degrees scaled by cos(latitude). */
    private static class Projection {

        private final double minLat, maxLat, minLon;
        private final double scale;

        Projection(RouteGraph graph) {
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            for (GraphEdge edge : graph.getEdges()) {
                for (Node node : edge.getPath()) {
                    minLat = Math.min(minLat, node.getLatitude());
                    maxLat = Math.max(maxLat, node.getLatitude());
                    minLon = Math.min(minLon, node.getLongitude());
                    maxLon = Math.max(maxLon, node.getLongitude());
                }
            }
            double lonScale = Math.cos(Math.toRadians((minLat + maxLat) / 2));
            double rangeX = Math.max((maxLon - minLon) * lonScale, 1e-9);
            double rangeY = Math.max(maxLat - minLat, 1e-9);
            this.scale = (WIDTH - 2 * PADDING) / rangeX;
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLon = minLon;
            this.height = rangeY * scale + 2 * PADDING;
        }

        private final double height;

        double x(Node node) {
            return PADDING + (node.getLongitude() - minLon)
                    * Math.cos(Math.toRadians((minLat + maxLat) / 2)) * scale;
        }

        double y(Node node) {
            return PADDING + (maxLat - node.getLatitude()) * scale;
        }

        double height() {
            return height;
        }
    }
}
