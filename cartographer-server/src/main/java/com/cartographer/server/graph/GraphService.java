package com.cartographer.server.graph;

import com.cartographer.io.GpxRouteLoader;
import com.cartographer.map.GraphBuilder;
import com.cartographer.map.GraphEdge;
import com.cartographer.map.Intersection;
import com.cartographer.map.IntersectionFinder;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Route;
import com.cartographer.server.config.ServerProperties;
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
import java.util.stream.Stream;

/**
 * Owns the graph the server serves. It is built from the configured routes
 * directory on startup and rebuilt from scratch after an upload. Readers never
 * see a half-built graph: the reference is swapped, the graph itself is never
 * mutated.
 */
@Service
public class GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphService.class);

    private final ServerProperties properties;
    private final GpxRouteLoader routeLoader;
    private final IntersectionFinder intersectionFinder;
    private final GraphBuilder graphBuilder;

    private volatile RouteGraph graph;

    public GraphService(ServerProperties properties, GpxRouteLoader routeLoader,
                        IntersectionFinder intersectionFinder, GraphBuilder graphBuilder) {
        this.properties = properties;
        this.routeLoader = routeLoader;
        this.intersectionFinder = intersectionFinder;
        this.graphBuilder = graphBuilder;
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
     * Reloads every GPX file in the routes directory and swaps in a freshly
     * built graph. Synchronized so concurrent rebuilds cannot interleave;
     * readers keep using the previous graph until the swap.
     */
    public synchronized void rebuild() {
        Path routesDir = properties.routesDir();
        List<Path> gpxFiles = listGpxFiles(routesDir);
        // one parse per file: XML builders are single-threaded, so parsing
        // maps cleanly onto parallel tasks (encounter order is preserved)
        List<Route> routes = gpxFiles.parallelStream().map(this::loadRoute).toList();
        List<Intersection> intersections = intersectionFinder.findWithSelfIntersections(routes);
        RouteGraph simplified = graphBuilder.simplify(graphBuilder.build(routes, intersections));
        this.graph = simplified;
        log.info("Loaded {} routes from {}", routes.size(), routesDir.toAbsolutePath());
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
