package com.cartographer.server.graph;

import com.cartographer.io.GpxRouteLoader;
import com.cartographer.map.GraphBuilder;
import com.cartographer.map.GraphEdge;
import com.cartographer.map.Intersection;
import com.cartographer.map.IntersectionFinder;
import com.cartographer.map.KalmanTrackFilter;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;
import com.cartographer.model.Route;
import com.cartographer.server.config.ServerProperties;
import com.cartographer.server.tiles.RoutesFingerprint;
import com.cartographer.server.tiles.TileCache;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Owns the graph the server serves. It is built from the configured routes
 * directory on startup and rebuilt from scratch after an upload. Readers never
 * see a half-built graph: the reference is swapped, the graph itself is never
 * mutated.
 *
 * <p>Every rebuild also brings the {@link TileCache} up to date with the new
 * graph, so the tile endpoint never encodes a tile on request. The upload
 * response waits for that, and the graph is published only once its tiles are in
 * place: under the rebuild lock, readers see the old graph with the old tiles or
 * the new graph with the new tiles, never a mix of the two.
 *
 * <p>When the routes directory and the tile settings are unchanged since the
 * tiles were last rendered, they are restored from the {@code MBTilesStore}
 * instead: the graph is still built ({@code /api/graph} needs it, and building
 * it is cheap compared with rendering), but the pre-render is skipped.
 */
@Service
public class GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphService.class);

    private final ServerProperties properties;
    private final GpxRouteLoader routeLoader;
    private final IntersectionFinder intersectionFinder;
    private final KalmanTrackFilter trackFilter;
    private final GraphBuilder graphBuilder;
    private final TileCache tileCache;

    private volatile RouteGraph graph;

    public GraphService(ServerProperties properties, GpxRouteLoader routeLoader,
                        IntersectionFinder intersectionFinder, KalmanTrackFilter trackFilter,
                        GraphBuilder graphBuilder, TileCache tileCache) {
        this.properties = properties;
        this.routeLoader = routeLoader;
        this.intersectionFinder = intersectionFinder;
        this.trackFilter = trackFilter;
        this.graphBuilder = graphBuilder;
        this.tileCache = tileCache;
    }

    @PostConstruct
    void buildOnStartup() {
        rebuild();
    }

    /** The simplified graph, complete as of the last successful build. */
    public RouteGraph graph() {
        return graph;
    }

    /**
     * Reloads every GPX file in the routes directory, swaps in a freshly built
     * graph and brings the tiles of that graph in place — restored from the store
     * when it was rendered from exactly these inputs, pre-rendered otherwise.
     * Synchronized so concurrent rebuilds cannot interleave; readers keep using
     * the previous graph and the previous tiles until the new pair is ready.
     */
    public synchronized void rebuild() {
        long startedAtNanos = System.nanoTime();
        Path routesDir = properties.routesDir();
        List<Path> gpxFiles = listGpxFiles(routesDir);
        String fingerprint = RoutesFingerprint.of(gpxFiles, properties.intersectionToleranceMeters(),
                properties.tileBufferUnits(), properties.tileCacheMinZoom(), properties.tileCacheMaxZoom());
        // one parse per file: XML builders are single-threaded, so parsing
        // maps cleanly onto parallel tasks (encounter order is preserved)
        List<Route> routes = gpxFiles.parallelStream().map(this::loadRoute).toList();
        int droppedPoints = dropDisplacedPoints(routes);
        List<Intersection> intersections = intersectionFinder.findWithSelfIntersections(routes);
        RouteGraph simplified = graphBuilder.simplify(graphBuilder.build(routes, intersections));
        long rebuildMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        log.info("Loaded {} routes from {} in {} ms, dropped {} track points as GPS spikes",
                routes.size(), routesDir.toAbsolutePath(), rebuildMillis, droppedPoints);
        // tiles first, then the graph: the new graph must never be visible over the old tiles
        if (!tileCache.restore(fingerprint)) {
            tileCache.preRender(simplified);
            tileCache.persist(fingerprint);
        }
        this.graph = simplified;
        logGraphStats(simplified);
    }

    /** A missing routes directory is a configuration error: fail instead of serving an empty map. */
    private List<Path> listGpxFiles(Path routesDir) {
        if (!Files.isDirectory(routesDir)) {
            throw new IllegalStateException("routes directory not found: " + routesDir.toAbsolutePath());
        }
        try (Stream<Path> files = Files.list(routesDir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".gpx"))
                    .sorted()
                    .toList();
        } catch (IOException ioException) {
            throw new UncheckedIOException("failed to list " + routesDir, ioException);
        }
    }

    private Route loadRoute(Path gpxFile) {
        try {
            return routeLoader.load(gpxFile);
        } catch (IOException ioException) {
            throw new UncheckedIOException("failed to parse " + gpxFile, ioException);
        }
    }

    /**
     * Replaces every route's recorded track with the points the spike filter
     * keeps, before anything downstream reads it, and returns how many points
     * that dropped.
     */
    private int dropDisplacedPoints(List<Route> routes) {
        int recorded = 0;
        int kept = 0;
        for (Route route : routes) {
            List<Node> track = route.getNodes();
            recorded += track.size();
            List<Node> filtered = trackFilter.filter(track);
            track.clear();
            track.addAll(filtered);
            kept += filtered.size();
        }
        return recorded - kept;
    }

    private void logGraphStats(RouteGraph graph) {
        long sharedEdges = graph.getEdges().stream().filter(GraphEdge::isShared).count();
        log.info("Simplified graph: {} vertices ({} junctions, {} endpoints), {} edges ({} shared), {} km total",
                graph.getNodes().size(),
                graph.getJunctions().size(),
                graph.getNodes().size() - graph.getJunctions().size(),
                graph.getEdges().size(),
                sharedEdges,
                String.format(Locale.ROOT, "%.2f", graph.totalLengthMeters() / 1000.0));
    }
}
