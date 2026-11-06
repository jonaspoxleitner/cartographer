package com.cartographer.server.tiles;

import com.cartographer.map.GraphEdge;
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
 * <p>A tile without any visible edge is the empty byte array — a vector tile
 * with zero layers — not an empty layer. The layer carries the edge properties
 * as {@code traversals}, {@code lengthMeters}, {@code shared} and
 * {@code routeCount} (MVT has no array type, so the route list is a count).
 */
public final class TileBuilder {

    /** Name of the single layer every tile carries. */
    public static final String LAYER_NAME = "edges";

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

    /**
     * The tile bytes for {@code x}/{@code y} at {@code zoom}: one feature per
     * visible run of an edge path, or the empty byte array when no edge reaches
     * the tile.
     */
    public byte[] buildTile(RouteGraph graph, int zoom, int x, int y) {
        int tilesPerAxis = SlippyMap.tilesPerAxis(zoom);
        if (x < 0 || x >= tilesPerAxis || y < 0 || y >= tilesPerAxis) {
            throw new IllegalArgumentException("tile " + x + "/" + y
                    + " is outside 0.." + (tilesPerAxis - 1) + " at zoom " + zoom);
        }

        MvtEncoder encoder = new MvtEncoder(LAYER_NAME, SlippyMap.TILE_EXTENT);
        int featureCount = 0;
        for (GraphEdge edge : graph.getEdges()) {
            List<List<TilePoint>> lines = clippedLines(edge, zoom, x, y);
            if (lines.isEmpty()) {
                continue;
            }
            Map<String, MvtValue> properties = properties(edge);
            for (List<TilePoint> line : lines) {
                encoder.addFeature(line, properties);
                featureCount++;
            }
        }
        return featureCount == 0 ? new byte[0] : encoder.toByteArray();
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

    private static Map<String, MvtValue> properties(GraphEdge edge) {
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
