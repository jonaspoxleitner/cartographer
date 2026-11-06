package com.cartographer.server.tiles;

import com.cartographer.map.GraphEdge;
import com.cartographer.map.GraphNode;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;
import com.cartographer.model.Route;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Address-range math of the tile pyramid, on a graph that spans a rectangle
 * inside tile {@value #TILE_X}/{@value #TILE_Y} at zoom {@value #ZOOM}. The
 * rectangle is expressed as fractions of that tile, so the expected addresses
 * are read off the same tile edges the pyramid reads, and doubling the zoom
 * doubles the indices: fractions 0.25..0.75 of a column become columns
 * {@code 2 * }{@value #TILE_X} and {@code 2 * }{@value #TILE_X}{@code  + 1}.
 */
class TilePyramidTest {

    private static final int ZOOM = 14;
    private static final int TILE_X = SlippyMap.tileX(13.0, ZOOM);
    private static final int TILE_Y = SlippyMap.tileY(48.0, ZOOM);
    /** The clipping buffer default, in tile units: 1/64 of a tile. */
    private static final int DEFAULT_BUFFER_UNITS = TileBuilder.DEFAULT_BUFFER_UNITS;

    @Test
    void boundsInsideOneTileNeedOnlyThatTile() {
        TilePyramid pyramid = TilePyramid.of(graphInsideTile(0.25, 0.75), 0);

        TilePyramid.TileRange range = pyramid.rangeAt(ZOOM);

        assertEquals(TILE_X, range.minX());
        assertEquals(TILE_X, range.maxX());
        assertEquals(TILE_Y, range.minY());
        assertEquals(TILE_Y, range.maxY());
        assertEquals(1, range.tileCount());
    }

    @Test
    void theSameBoundsSpanTwoColumnsAndRowsOneZoomDeeper() {
        TilePyramid pyramid = TilePyramid.of(graphInsideTile(0.25, 0.75), 0);

        TilePyramid.TileRange range = pyramid.rangeAt(ZOOM + 1);

        assertEquals(2 * TILE_X, range.minX());
        assertEquals(2 * TILE_X + 1, range.maxX());
        assertEquals(2 * TILE_Y, range.minY());
        assertEquals(2 * TILE_Y + 1, range.maxY());
        assertEquals(4, range.tileCount());
    }

    @Test
    void bufferPullsInTheNeighbouringTileItsEdgesReach() {
        // 0.01 of a tile from each edge, well inside the 1/64-of-a-tile buffer
        TilePyramid pyramid = TilePyramid.of(graphInsideTile(0.01, 0.99), DEFAULT_BUFFER_UNITS);

        TilePyramid.TileRange range = pyramid.rangeAt(ZOOM);

        assertEquals(TILE_X - 1, range.minX());
        assertEquals(TILE_X + 1, range.maxX());
        assertEquals(TILE_Y - 1, range.minY());
        assertEquals(TILE_Y + 1, range.maxY());
        assertEquals(9, range.tileCount());
    }

    @Test
    void bufferThatDoesNotReachTheEdgeKeepsTheRangeAtOneTile() {
        // 0.02 of a tile from each edge: further than the 1/64-of-a-tile buffer
        TilePyramid pyramid = TilePyramid.of(graphInsideTile(0.02, 0.98), DEFAULT_BUFFER_UNITS);

        TilePyramid.TileRange range = pyramid.rangeAt(ZOOM);

        assertEquals(1, range.tileCount());
        assertEquals(TILE_X, range.minX());
        assertEquals(TILE_Y, range.minY());
    }

    @Test
    void coordinateExactlyOnATileEdgeStartsAtTheTileEastAndSouthOfIt() {
        // zoom 1: the prime meridian is exactly the edge between columns 0 and 1 and
        // latitude 0 exactly the edge between rows 0 and 1, so the tile indices are
        // exact binary fractions rather than an epsilon away from the edge
        TilePyramid pyramid = TilePyramid.of(graph(0.0, 0.0, 0.0, 0.0), 0);

        TilePyramid.TileRange range = pyramid.rangeAt(1);

        // floor semantics, as SlippyMap.tileX/tileY: the edge belongs to the tile east/south of it
        assertEquals(1, range.minX());
        assertEquals(1, range.maxX());
        assertEquals(1, range.minY());
        assertEquals(1, range.maxY());
        assertEquals(1, range.tileCount());
    }

    @Test
    void bufferCrossesATileEdgeTheGraphSitsOn() {
        TilePyramid pyramid = TilePyramid.of(graph(0.0, 0.0, 0.0, 0.0), DEFAULT_BUFFER_UNITS);

        TilePyramid.TileRange range = pyramid.rangeAt(1);

        // both tiles along the shared edge draw the stretch lying on it
        assertEquals(0, range.minX());
        assertEquals(1, range.maxX());
        assertEquals(0, range.minY());
        assertEquals(1, range.maxY());
        assertEquals(4, range.tileCount());
    }

    @Test
    void rangeStaysInsideTheGridAtTheWorldEdge() {
        TilePyramid pyramid = TilePyramid.of(graph(-180.0, 180.0, 85.0, -85.0), DEFAULT_BUFFER_UNITS);

        TilePyramid.TileRange range = pyramid.rangeAt(2);

        // the buffer reaches past the world edge, which must not become an address off the grid
        assertEquals(0, range.minX());
        assertEquals(3, range.maxX());
        assertEquals(0, range.minY());
        assertEquals(3, range.maxY());
        assertEquals(16, range.tileCount());
    }

    @Test
    void graphWithoutAnEdgeHasNoTileAtAnyZoom() {
        TilePyramid pyramid = TilePyramid.of(new RouteGraph(), DEFAULT_BUFFER_UNITS);

        for (int zoom = 0; zoom <= SlippyMap.MAX_ZOOM; zoom++) {
            assertEquals(0, pyramid.rangeAt(zoom).tileCount(), "tiles at zoom " + zoom);
        }
    }

    @Test
    void negativeBufferIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TilePyramid.of(graphInsideTile(0.25, 0.75), -1));
    }

    /** A one-edge graph whose path spans the tile fractions {@code from}..{@code to} in both directions. */
    private static RouteGraph graphInsideTile(double from, double to) {
        return graph(longitudeAt(from), longitudeAt(to), latitudeAt(from), latitudeAt(to));
    }

    /** A one-edge graph from the north-west corner to the south-east corner of a box. */
    private static RouteGraph graph(double west, double east, double north, double south) {
        Node northWest = new Node(north, west);
        Node southEast = new Node(south, east);
        Route route = new Route("route-a");
        route.addNode(northWest);
        route.addNode(southEast);

        RouteGraph graph = new RouteGraph();
        GraphNode from = graph.addEndpoint(northWest);
        GraphNode to = graph.addEndpoint(southEast);
        graph.addEdge(new GraphEdge(from, to, List.of(northWest, southEast), route));
        return graph;
    }

    private static double longitudeAt(double tileFraction) {
        return SlippyMap.longitudeForTileX(TILE_X + tileFraction, ZOOM);
    }

    private static double latitudeAt(double tileFraction) {
        return SlippyMap.latitudeForTileY(TILE_Y + tileFraction, ZOOM);
    }
}