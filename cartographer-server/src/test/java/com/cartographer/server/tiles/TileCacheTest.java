package com.cartographer.server.tiles;

import com.cartographer.map.GraphEdge;
import com.cartographer.map.GraphNode;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;
import com.cartographer.model.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pre-render snapshot, over the one-edge fixture the tile endpoint tests
 * use: from the centre of tile {@value #TILE_X}/{@value #TILE_Y} at zoom
 * {@value #ZOOM} to the centre of the tile east of it. Its bounding box covers
 * the two columns of that row, so the snapshot holds exactly those two
 * addresses and a request for any other address gets the empty tile.
 *
 * <p>Every test uses its own MBTiles file in the temp directory, so a snapshot
 * written by one test can never be restored by another.
 */
class TileCacheTest {

    private static final int ZOOM = 14;
    private static final int TILE_X = SlippyMap.tileX(13.0, ZOOM);
    private static final int TILE_Y = SlippyMap.tileY(48.0, ZOOM);
    private static final double CENTER_LATITUDE = SlippyMap.latitudeForTileY(TILE_Y + 0.5, ZOOM);
    private static final double CENTER_LONGITUDE = SlippyMap.longitudeForTileX(TILE_X + 0.5, ZOOM);
    private static final double TILE_WIDTH_DEGREES = 360.0 / (1 << ZOOM);
    private static final String FINGERPRINT = "fingerprint-a";

    @TempDir
    Path tempDir;

    @Test
    void preRenderedTileIsTheTileTheBuilderEncodes() {
        RouteGraph graph = fixtureGraph();
        TileCache cache = cache();

        cache.preRender(graph);

        byte[] onDemand = new TileBuilder().buildTile(graph, ZOOM, TILE_X, TILE_Y);
        assertTrue(onDemand.length > 0, "fixture tile should hold the edge");
        TilePayload payload = cache.payload(ZOOM, TILE_X, TILE_Y);
        assertArrayEquals(onDemand, payload.raw());
        assertArrayEquals(TilePayload.of(onDemand).gzipped(), payload.gzipped());
        assertEquals(TilePayload.of(onDemand).etag(), payload.etag());
    }

    @Test
    void preRenderHoldsEveryAddressOfThePyramidAndNothingElse() {
        TileCache cache = cache();

        cache.preRender(fixtureGraph());

        // the edge's bounding box: the fixture column and the one east of it, one row
        assertEquals(Set.of(new TileCache.TileAddress(ZOOM, TILE_X, TILE_Y),
                new TileCache.TileAddress(ZOOM, TILE_X + 1, TILE_Y)), cache.tiles().keySet());
    }

    @Test
    void addressOutsideThePreRenderedSetIsTheEmptyTile() {
        TileCache cache = cache();
        cache.preRender(fixtureGraph());
        // a quarter of the world east: inside the slippy grid, no edge, not pre-rendered
        int elsewhere = SlippyMap.tileX(CENTER_LONGITUDE + 90.0, ZOOM);

        TilePayload payload = cache.payload(ZOOM, elsewhere, TILE_Y);

        assertEquals(0, payload.raw().length);
        assertEquals(TilePayload.of(new byte[0]).etag(), payload.etag());
    }

    @Test
    void preRenderReplacesThePreviousSnapshot() {
        TileCache cache = cache();
        cache.preRender(fixtureGraph());

        cache.preRender(new RouteGraph());

        assertTrue(cache.tiles().isEmpty(), "the empty graph has no tile to offer");
        assertEquals(0, cache.payload(ZOOM, TILE_X, TILE_Y).raw().length);
    }

    @Test
    void restoreBringsBackThePersistedSnapshotByteForByte() {
        TileCache preRendered = cache();
        preRendered.preRender(fixtureGraph());
        preRendered.persist(FINGERPRINT);

        TileCache restarted = cache();
        boolean restored = restarted.restore(FINGERPRINT);

        assertTrue(restored, "a store written for the same fingerprint must be restored");
        assertEquals(preRendered.tiles().keySet(), restarted.tiles().keySet());
        TilePayload before = preRendered.payload(ZOOM, TILE_X + 1, TILE_Y);
        TilePayload after = restarted.payload(ZOOM, TILE_X + 1, TILE_Y);
        assertArrayEquals(before.raw(), after.raw());
        assertArrayEquals(before.gzipped(), after.gzipped());
        assertEquals(before.etag(), after.etag());
    }

    @Test
    void restoreOfAnotherFingerprintLeavesTheSnapshotEmpty() {
        TileCache preRendered = cache();
        preRendered.preRender(fixtureGraph());
        preRendered.persist(FINGERPRINT);

        TileCache restarted = cache();
        boolean restored = restarted.restore("fingerprint-b");

        assertFalse(restored, "a store rendered from other inputs must not be served");
        assertTrue(restarted.tiles().isEmpty());
        assertEquals(0, restarted.payload(ZOOM, TILE_X, TILE_Y).raw().length);
    }

    @Test
    void restoreWithoutAStoreIsNotRestored() {
        assertFalse(cache().restore(FINGERPRINT));
    }

    @Test
    void zoomRangeOutsideTheTileGridIsRejected() {
        TileBuilder builder = new TileBuilder();
        MBTilesStore store = new MBTilesStore(tempDir.resolve("tiles.mbtiles"));
        assertThrows(IllegalArgumentException.class, () -> new TileCache(builder, -1, ZOOM, store));
        assertThrows(IllegalArgumentException.class,
                () -> new TileCache(builder, ZOOM, SlippyMap.MAX_ZOOM + 1, store));
        assertThrows(IllegalArgumentException.class, () -> new TileCache(builder, ZOOM, ZOOM - 1, store));
    }

    /** A cache over this test's own store, as a fresh start would build it. */
    private TileCache cache() {
        return new TileCache(new TileBuilder(), ZOOM, ZOOM,
                new MBTilesStore(tempDir.resolve("tiles.mbtiles")));
    }

    /** The fixture edge, from the tile centre to the centre of the next tile. */
    private static RouteGraph fixtureGraph() {
        Node start = new Node(CENTER_LATITUDE, CENTER_LONGITUDE);
        Node end = new Node(CENTER_LATITUDE, CENTER_LONGITUDE + TILE_WIDTH_DEGREES);
        Route route = new Route("route-a");
        route.addNode(start);
        route.addNode(end);

        RouteGraph graph = new RouteGraph();
        GraphNode from = graph.addEndpoint(start);
        GraphNode to = graph.addEndpoint(end);
        graph.addEdge(new GraphEdge(from, to, List.of(start, end), route));
        return graph;
    }
}
