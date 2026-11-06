package com.cartographer.server.tiles;

import com.cartographer.map.GraphEdge;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;

/**
 * The tiles a graph can show, expressed as one closed range of columns and
 * rows per zoom level.
 *
 * <p>A tile shows an edge when the edge reaches the tile box widened by the
 * clipping buffer, so the pyramid is the bounding box over every path point,
 * widened by that buffer, read as tile indices. The bounding box
 * over-approximates: a graph running diagonally leaves the corners of its box
 * without an edge, and those addresses belong to the pyramid as empty tiles,
 * which cost nothing to serve.
 *
 * <p>A coordinate lying exactly on a tile edge counts as the tile east and
 * south of it, as {@link SlippyMap#tileX} and {@link SlippyMap#tileY} do; with
 * a non-zero buffer the ranges cross that edge anyway.
 */
public final class TilePyramid {

    /** The pyramid over a graph without a path point: no tile can show it. */
    private static final TilePyramid NONE = new TilePyramid();

    /** What {@link #rangeAt} reports for the empty pyramid: an address range without any address. */
    private static final TileRange NO_TILES = new TileRange(0, -1, 0, -1);

    private final double westMercator;
    private final double northMercator;
    private final double eastMercator;
    private final double southMercator;
    /** The clipping buffer as a fraction of one tile: {@code bufferUnits / TILE_EXTENT}. */
    private final double bufferFraction;
    private final boolean empty;

    private TilePyramid() {
        this.empty = true;
        this.westMercator = 0.0;
        this.northMercator = 0.0;
        this.eastMercator = 0.0;
        this.southMercator = 0.0;
        this.bufferFraction = 0.0;
    }

    private TilePyramid(double westMercator, double northMercator, double eastMercator, double southMercator,
                        double bufferFraction) {
        this.empty = false;
        this.westMercator = westMercator;
        this.northMercator = northMercator;
        this.eastMercator = eastMercator;
        this.southMercator = southMercator;
        this.bufferFraction = bufferFraction;
    }

    /**
     * The pyramid over every path point of {@code graph}.
     *
     * @param bufferUnits the clipping buffer the tile builder widens every tile
     *     box by, in tile units of {@value SlippyMap#TILE_EXTENT}: a tile within
     *     that distance of the graph box still draws part of an edge
     */
    public static TilePyramid of(RouteGraph graph, int bufferUnits) {
        if (bufferUnits < 0) {
            throw new IllegalArgumentException("buffer must not be negative: " + bufferUnits);
        }
        double west = Double.POSITIVE_INFINITY;
        double south = Double.POSITIVE_INFINITY;
        double east = Double.NEGATIVE_INFINITY;
        double north = Double.NEGATIVE_INFINITY;
        for (GraphEdge edge : graph.getEdges()) {
            for (Node node : edge.getPath()) {
                west = Math.min(west, node.getLongitude());
                east = Math.max(east, node.getLongitude());
                south = Math.min(south, node.getLatitude());
                north = Math.max(north, node.getLatitude());
            }
        }
        if (west > east) {
            return NONE;
        }
        return new TilePyramid(SlippyMap.mercatorX(west), SlippyMap.mercatorY(north),
                SlippyMap.mercatorX(east), SlippyMap.mercatorY(south),
                (double) bufferUnits / SlippyMap.TILE_EXTENT);
    }

    /**
     * The addresses that can show the graph at {@code zoom}, or a range without
     * any address when the graph has no path point.
     */
    public TileRange rangeAt(int zoom) {
        int tiles = SlippyMap.tilesPerAxis(zoom);
        if (empty) {
            return NO_TILES;
        }
        return new TileRange(tileAt(westMercator * tiles - bufferFraction, tiles),
                tileAt(eastMercator * tiles + bufferFraction, tiles),
                tileAt(northMercator * tiles - bufferFraction, tiles),
                tileAt(southMercator * tiles + bufferFraction, tiles));
    }

    /**
     * The tile index holding {@code position}, clamped into the grid: a buffer
     * reaching past the world edge must not produce an address off the grid.
     */
    private static int tileAt(double position, int tiles) {
        return (int) Math.max(0, Math.min(tiles - 1, Math.floor(position)));
    }

    /**
     * A closed rectangle of tile columns {@code minX..maxX} and rows
     * {@code minY..maxY} at one zoom level. The range holding no address has
     * {@code maxX < minX} and {@code maxY < minY}, so its counts are zero.
     */
    public record TileRange(int minX, int maxX, int minY, int maxY) {

        /** How many addresses the range holds. */
        public int tileCount() {
            return (maxX - minX + 1) * (maxY - minY + 1);
        }
    }
}
