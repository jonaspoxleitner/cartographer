package com.cartographer.server.tiles;

import com.cartographer.map.GraphEdge;
import com.cartographer.map.GraphNode;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;
import com.cartographer.server.tiles.MvtEncoder.MvtValue;
import com.cartographer.server.tiles.MvtEncoder.TilePoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the MVT bytes for one z/x/y address out of the {@link RouteGraph}.
 *
 * <p>Each edge is projected into continuous tile-local coordinates, kept when
 * its bounding box reaches the tile box (expanded by the buffer), and clipped
 * against that box segment by segment. Clipping happens before rounding, so a
 * path crossing a tile boundary is cut at exactly the shared edge coordinate
 * and neighbouring tiles place the boundary point identically. The surviving
 * runs are rounded to tile-local integers and written as line features of the
 * {@code edges} layer; consecutive points that round to the same integer are
 * dropped and a run left with fewer than two points is omitted.
 *
 * <p>A tile without any visible edge or junction is the empty byte array — a
 * vector tile with zero layers — not an empty layer. The {@code edges} layer
 * carries the edge properties as {@code traversals}, {@code lengthMeters},
 * {@code shared} and {@code routeCount} (MVT has no array type, so the route
 * list is a count).
 *
 * <p>The {@code junctions} layer holds one point feature per junction vertex
 * inside the buffered tile box, in the same tile-local coordinates as the paths,
 * so a dot sits exactly on the shared endpoint of that junction's edges. It
 * carries no properties.
 */
public final class TileBuilder {

    /** Name of the layer every visible edge path is written to. */
    public static final String LAYER_NAME = "edges";

    /** Name of the layer the junction dots are written to. */
    public static final String JUNCTION_LAYER_NAME = "junctions";

    /** Default clip buffer around the tile box, in tile units of {@value SlippyMap#TILE_EXTENT}. */
    public static final int DEFAULT_BUFFER_UNITS = 64;

    private final int bufferUnits;

    public TileBuilder() {
        this(DEFAULT_BUFFER_UNITS);
    }

    /**
     * @param bufferUnits how far outside the tile box, in tile units, a segment
     *     is still drawn so lines running along a tile edge do not show seams
     */
    public TileBuilder(int bufferUnits) {
        if (bufferUnits < 0) {
            throw new IllegalArgumentException("buffer must not be negative: " + bufferUnits);
        }
        this.bufferUnits = bufferUnits;
    }

    /** The clipping buffer every tile box is widened by, in tile units of {@value SlippyMap#TILE_EXTENT}. */
    public int bufferUnits() {
        return bufferUnits;
    }

    /**
     * The tile bytes for {@code x}/{@code y} at {@code zoom}: the visible runs of
     * the edge paths and a dot for every junction reaching the tile, or the empty
     * byte array when neither does.
     */
    public byte[] buildTile(RouteGraph graph, int zoom, int x, int y) {
        int tilesPerAxis = SlippyMap.tilesPerAxis(zoom);
        if (x < 0 || x >= tilesPerAxis || y < 0 || y >= tilesPerAxis) {
            throw new IllegalArgumentException("tile " + x + "/" + y
                    + " is outside 0.." + (tilesPerAxis - 1) + " at zoom " + zoom);
        }

        MvtEncoder edgeLayer = new MvtEncoder(LAYER_NAME, SlippyMap.TILE_EXTENT);
        int edgeFeatures = 0;
        for (GraphEdge edge : graph.getEdges()) {
            List<List<TilePoint>> lines = clippedLines(edge, zoom, x, y);
            if (lines.isEmpty()) {
                continue;
            }
            Map<String, MvtValue> properties = edgeProperties(edge);
            for (List<TilePoint> line : lines) {
                edgeLayer.addFeature(line, properties);
                edgeFeatures++;
            }
        }

        MvtEncoder junctionLayer = new MvtEncoder(JUNCTION_LAYER_NAME, SlippyMap.TILE_EXTENT);
        int junctionFeatures = 0;
        for (GraphNode junction : graph.getJunctions()) {
            TilePoint point = junctionPoint(junction, zoom, x, y);
            if (point != null) {
                junctionLayer.addPointFeature(point, Map.of());
                junctionFeatures++;
            }
        }

        List<MvtEncoder> layers = new ArrayList<>(2);
        if (edgeFeatures > 0) {
            layers.add(edgeLayer);
        }
        if (junctionFeatures > 0) {
            layers.add(junctionLayer);
        }
        return layers.isEmpty() ? new byte[0] : MvtEncoder.tile(layers);
    }

    /**
     * The junction's tile-local coordinate, or {@code null} when it lies outside
     * the buffered tile box. A junction's coordinate is the last point of every
     * incident edge path, so the dot lands exactly on their shared endpoint.
     */
    private TilePoint junctionPoint(GraphNode junction, int zoom, int tileX, int tileY) {
        double[] projected = project(junction.getLocation(), SlippyMap.tilesPerAxis(zoom), tileX, tileY);
        double min = -bufferUnits;
        double max = SlippyMap.TILE_EXTENT + bufferUnits;
        if (projected[0] < min || projected[0] > max || projected[1] < min || projected[1] > max) {
            return null;
        }
        return new TilePoint((int) Math.round(projected[0]), (int) Math.round(projected[1]));
    }

    /** The visible, collapsed runs of one edge inside the buffered tile box. */
    private List<List<TilePoint>> clippedLines(GraphEdge edge, int zoom, int tileX, int tileY) {
        double tilesPerAxis = SlippyMap.tilesPerAxis(zoom);
        List<double[]> path = new ArrayList<>(edge.getPath().size());
        for (Node node : edge.getPath()) {
            path.add(project(node, tilesPerAxis, tileX, tileY));
        }

        double min = -bufferUnits;
        double max = SlippyMap.TILE_EXTENT + bufferUnits;
        if (!intersectsBox(path, min, max)) {
            return List.of();
        }
        return clipToBox(path, min, max);
    }

    /** Web Mercator position of the node in tile-local units, unclamped so the buffer has room. */
    private static double[] project(Node node, double tilesPerAxis, int tileX, int tileY) {
        double x = (SlippyMap.mercatorX(node.getLongitude()) * tilesPerAxis - tileX) * SlippyMap.TILE_EXTENT;
        double y = (SlippyMap.mercatorY(node.getLatitude()) * tilesPerAxis - tileY) * SlippyMap.TILE_EXTENT;
        return new double[] {x, y};
    }

    /** Cheap rejection: no edge point within the box means no segment can be. */
    private static boolean intersectsBox(List<double[]> points, double min, double max) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double[] point : points) {
            minX = Math.min(minX, point[0]);
            maxX = Math.max(maxX, point[0]);
            minY = Math.min(minY, point[1]);
            maxY = Math.max(maxY, point[1]);
        }
        return maxX >= min && minX <= max && maxY >= min && minY <= max;
    }

    /**
     * Clips the path against the box, Liang–Barsky per segment. A segment that
     * leaves the box ends the current run; a segment that enters it starts a new
     * one, so a path that leaves and re-enters yields several features.
     */
    private static List<List<TilePoint>> clipToBox(List<double[]> path, double min, double max) {
        List<List<TilePoint>> lines = new ArrayList<>();
        List<double[]> run = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            VisibleSegment visible = clipSegment(path.get(i - 1), path.get(i), min, max);
            if (visible == null) {
                flushRun(run, lines);
                continue;
            }
            if (visible.entersBox()) {
                flushRun(run, lines);
            }
            if (run.isEmpty()) {
                run.add(new double[] {visible.startX(), visible.startY()});
            }
            run.add(new double[] {visible.endX(), visible.endY()});
        }
        flushRun(run, lines);
        return lines;
    }

    /**
     * The part of segment {@code a}→{@code b} inside the box, or {@code null}
     * when the segment misses it. {@code startsAtSegmentStart} tells whether the
     * visible part begins where the segment does, i.e. whether it continues the
     * previous segment's run.
     */
    private static VisibleSegment clipSegment(double[] a, double[] b, double min, double max) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double[] direction = {-dx, dx, -dy, dy};
        double[] distance = {a[0] - min, max - a[0], a[1] - min, max - a[1]};
        double enter = 0;
        double exit = 1;
        for (int edge = 0; edge < direction.length; edge++) {
            if (direction[edge] == 0) {
                if (distance[edge] < 0) {
                    return null; // parallel to this edge and outside it
                }
                continue;
            }
            double t = distance[edge] / direction[edge];
            if (direction[edge] < 0) {
                if (t > exit) {
                    return null;
                }
                enter = Math.max(enter, t);
            } else {
                if (t < enter) {
                    return null;
                }
                exit = Math.min(exit, t);
            }
        }
        return new VisibleSegment(enter, a[0] + enter * dx, a[1] + enter * dy, a[0] + exit * dx, a[1] + exit * dy);
    }

    /** Rounds the run to tile integers, drops repeated points and keeps it if it is still a line. */
    private static void flushRun(List<double[]> run, List<List<TilePoint>> lines) {
        List<TilePoint> points = new ArrayList<>(run.size());
        for (double[] point : run) {
            TilePoint tilePoint = new TilePoint((int) Math.round(point[0]), (int) Math.round(point[1]));
            if (points.isEmpty() || !points.getLast().equals(tilePoint)) {
                points.add(tilePoint);
            }
        }
        run.clear();
        if (points.size() >= 2) {
            lines.add(List.copyOf(points));
        }
    }

    private static Map<String, MvtValue> edgeProperties(GraphEdge edge) {
        Map<String, MvtValue> properties = new LinkedHashMap<>();
        properties.put("traversals", MvtValue.of(edge.getTraversals()));
        properties.put("lengthMeters", MvtValue.of(edge.getLengthMeters()));
        properties.put("shared", MvtValue.of(edge.isShared()));
        properties.put("routeCount", MvtValue.of(edge.getRoutes().size()));
        return properties;
    }

    /** A segment's visible part as continuous tile coordinates. */
    private record VisibleSegment(double enter, double startX, double startY, double endX, double endY) {

        boolean entersBox() {
            return enter > 0;
        }
    }
}
