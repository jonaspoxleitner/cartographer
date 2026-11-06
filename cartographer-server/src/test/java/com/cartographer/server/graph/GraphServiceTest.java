package com.cartographer.server.graph;

import com.cartographer.io.GpxRouteLoader;
import com.cartographer.map.GraphBuilder;
import com.cartographer.map.GraphEdge;
import com.cartographer.map.IntersectionFinder;
import com.cartographer.map.RouteGraph;
import com.cartographer.server.config.ServerProperties;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Builds the server's graph from the fixture routes directory. route-a runs
 * west-east, route-b crosses it at (48.0, 13.002): one junction, four
 * endpoints, four edges — the counts the CLI prints for the same files.
 */
class GraphServiceTest {

    private static final double TOLERANCE_METERS = 10.0;
    private static final Path ROUTES_DIR = resourcePath("/gpx");

    private GraphService serviceWithRoutesDir(Path routesDir) {
        return new GraphService(new ServerProperties(TOLERANCE_METERS, routesDir),
                new GpxRouteLoader(),
                new IntersectionFinder(TOLERANCE_METERS),
                new GraphBuilder(TOLERANCE_METERS));
    }

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
    void startupBuildFailsOnMissingRoutesDirectory() {
        GraphService service = serviceWithRoutesDir(ROUTES_DIR.resolve("missing"));

        assertThrows(IllegalStateException.class, service::buildOnStartup);
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
