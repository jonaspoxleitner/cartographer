package com.cartographer.server.graph;

import com.cartographer.io.GpxRouteLoader;
import com.cartographer.map.GraphBuilder;
import com.cartographer.map.GraphEdge;
import com.cartographer.map.IntersectionFinder;
import com.cartographer.map.KalmanTrackFilter;
import com.cartographer.map.RouteGraph;
import com.cartographer.server.config.ServerProperties;
import com.cartographer.server.tiles.MBTilesStore;
import com.cartographer.server.tiles.RoutesFingerprint;
import com.cartographer.server.tiles.SlippyMap;
import com.cartographer.server.tiles.TileBuilder;
import com.cartographer.server.tiles.TileCache;
import com.cartographer.server.tiles.TileCache.TileAddress;
import com.cartographer.server.tiles.TilePayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the server's graph from the fixture routes directory. route-a runs
 * west-east, route-b crosses it at (48.0, 13.002): one junction, four
 * endpoints, four edges — the counts the CLI prints for the same files.
 *
 * <p>The crossing lies in tile {@value #ZOOM}/{@value #TILE_X}/{@value #TILE_Y},
 * the address the persistence tests below use: a payload planted in the store
 * for exactly the fingerprint the rebuild computes survives only when the
 * rebuild skips the pre-render.
 */
class GraphServiceTest {

    private static final double TOLERANCE_METERS = 10.0;
    private static final int TILE_CACHE_MIN_ZOOM = 0;
    private static final int TILE_CACHE_MAX_ZOOM = 18;
    private static final int TILE_CACHE_MAX_AGE_SECONDS = 3600;
    private static final Path ROUTES_DIR = resourcePath("/gpx");
    private static final List<String> FIXTURES = List.of("route-a.gpx", "route-b.gpx");
    private static final int ZOOM = 14;
    private static final int TILE_X = SlippyMap.tileX(13.002, ZOOM);
    private static final int TILE_Y = SlippyMap.tileY(48.0, ZOOM);
    /** Not valid MVT: the tile is never decoded here, only checked for identity. */
    private static final byte[] PLANTED_TILE = "tile the store had".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private TileCache tileCache;

    @Test
    void buildsGraphFromRoutesDirectory() {
        GraphService service = serviceWithRoutesDir(ROUTES_DIR);

        service.rebuild();

        RouteGraph graph = service.graph();
        assertEquals(5, graph.getNodes().size());
        assertEquals(1, graph.getJunctions().size());
        assertEquals(4, graph.getEdges().size());
        assertEquals(0, graph.getEdges().stream().filter(GraphEdge::isShared).count());
        assertEquals(445.0, graph.totalLengthMeters(), 1.0);
    }

    @Test
    void rebuildPreRendersTheTilesOfTheNewGraph() {
        GraphService service = serviceWithRoutesDir(ROUTES_DIR);

        service.rebuild();

        assertTrue(crossingTile().raw().length > 0, "a rebuild should leave the new graph's tiles in the cache");
    }

    @Test
    void rebuildRestoresTheStoredTilesWhenTheRoutesAndTileSettingsAreUnchanged() {
        // a store written for exactly these inputs, holding a tile no other code produces
        store().write(fingerprintOf(ROUTES_DIR, TOLERANCE_METERS),
                Map.of(crossingAddress(), TilePayload.of(PLANTED_TILE)));

        serviceWithRoutesDir(ROUTES_DIR).rebuild();

        assertArrayEquals(PLANTED_TILE, crossingTile().raw(),
                "the stored tiles must be served, which means the pre-render was skipped");
    }

    @Test
    void rebuildPreRendersAgainWhenOnlyTheIntersectionToleranceChanged() {
        // the tolerance decides which edges exist, hence what the tiles contain
        store().write(fingerprintOf(ROUTES_DIR, TOLERANCE_METERS + 1.0),
                Map.of(crossingAddress(), TilePayload.of(PLANTED_TILE)));

        serviceWithRoutesDir(ROUTES_DIR).rebuild();

        assertFalse(Arrays.equals(PLANTED_TILE, crossingTile().raw()),
                "tiles rendered under another tolerance must not be restored");
        assertTrue(crossingTile().raw().length > 0, "the fixtures should render a tile");
    }

    @Test
    void rebuildPreRendersAgainWhenAGpxFileChangedSinceTheStoreWasWritten() throws IOException {
        Path routesDir = copyFixturesToTempDir();
        store().write(fingerprintOf(routesDir, TOLERANCE_METERS),
                Map.of(crossingAddress(), TilePayload.of(PLANTED_TILE)));
        // a re-uploaded file of the same size and content still gets a new modification time
        Files.setLastModifiedTime(routesDir.resolve("route-a.gpx"),
                FileTime.fromMillis(System.currentTimeMillis() + 1000));

        serviceWithRoutesDir(routesDir).rebuild();

        assertTrue(crossingTile().raw().length > 0, "changed routes must be rendered, not restored");
        assertFalse(Arrays.equals(PLANTED_TILE, crossingTile().raw()),
                "the planted tile must have been replaced by a rendered one");
    }

    @Test
    void restartWithUnchangedRoutesServesTheSameBytesAndEtag() {
        serviceWithRoutesDir(ROUTES_DIR).rebuild();
        TilePayload rendered = crossingTile();
        assertTrue(rendered.raw().length > 0, "the fixture crossing should render a tile");

        // a second start: fresh cache, fresh graph service, same routes and store
        serviceWithRoutesDir(ROUTES_DIR).rebuild();
        TilePayload restored = crossingTile();

        assertArrayEquals(rendered.raw(), restored.raw());
        assertArrayEquals(rendered.gzipped(), restored.gzipped());
        assertEquals(rendered.etag(), restored.etag());
    }

    @Test
    void startupBuildFailsOnMissingRoutesDirectory() {
        GraphService service = serviceWithRoutesDir(ROUTES_DIR.resolve("missing"));

        assertThrows(IllegalStateException.class, service::buildOnStartup);
    }

    /** A service over {@code routesDir} with a fresh cache on this test's store, as a start would build it. */
    private GraphService serviceWithRoutesDir(Path routesDir) {
        tileCache = new TileCache(new TileBuilder(), TILE_CACHE_MIN_ZOOM, TILE_CACHE_MAX_ZOOM, store());
        return new GraphService(
                new ServerProperties(TOLERANCE_METERS, routesDir, TileBuilder.DEFAULT_BUFFER_UNITS,
                        TILE_CACHE_MIN_ZOOM, TILE_CACHE_MAX_ZOOM, TILE_CACHE_MAX_AGE_SECONDS, tilesFile()),
                new GpxRouteLoader(),
                new IntersectionFinder(TOLERANCE_METERS),
                new KalmanTrackFilter(),
                new GraphBuilder(TOLERANCE_METERS),
                tileCache);
    }

    private MBTilesStore store() {
        return new MBTilesStore(tilesFile());
    }

    private Path tilesFile() {
        return tempDir.resolve("tiles.mbtiles");
    }

    /** The fingerprint a rebuild over {@code routesDir} with this test's settings computes. */
    private static String fingerprintOf(Path routesDir, double toleranceMeters) {
        try (var files = Files.list(routesDir)) {
            List<Path> gpxFiles = files.filter(file -> file.getFileName().toString().endsWith(".gpx")).toList();
            return RoutesFingerprint.of(gpxFiles, toleranceMeters, TileBuilder.DEFAULT_BUFFER_UNITS,
                    TILE_CACHE_MIN_ZOOM, TILE_CACHE_MAX_ZOOM);
        } catch (IOException ioException) {
            throw new UncheckedIOException("failed to list " + routesDir, ioException);
        }
    }

    private static TileAddress crossingAddress() {
        return new TileAddress(ZOOM, TILE_X, TILE_Y);
    }

    private TilePayload crossingTile() {
        return tileCache.payload(ZOOM, TILE_X, TILE_Y);
    }

    private Path copyFixturesToTempDir() throws IOException {
        Path routesDir = Files.createDirectory(tempDir.resolve("routes"));
        for (String fixture : FIXTURES) {
            Files.copy(ROUTES_DIR.resolve(fixture), routesDir.resolve(fixture));
        }
        return routesDir;
    }

    private static Path resourcePath(String name) {
        try {
            return Path.of(Objects.requireNonNull(GraphServiceTest.class.getResource(name),
                    "missing test resource " + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("not a file path: " + name, e);
        }
    }
}
