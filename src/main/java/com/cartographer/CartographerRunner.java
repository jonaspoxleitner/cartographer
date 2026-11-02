package com.cartographer;

import com.cartographer.config.CartographerProperties;
import com.cartographer.io.GpxRouteLoader;
import com.cartographer.map.GraphBuilder;
import com.cartographer.map.GraphEdge;
import com.cartographer.map.Intersection;
import com.cartographer.map.IntersectionFinder;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;
import com.cartographer.model.Route;
import com.cartographer.ui.SvgGraphRenderer;
import com.cartographer.ui.TextGraphRenderer;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Runs the CLI pipeline: load routes, build the graph, render it. */
@Service
public class CartographerRunner {

    private final CartographerProperties properties;
    private final GpxRouteLoader routeLoader;
    private final IntersectionFinder intersectionFinder;
    private final GraphBuilder graphBuilder;
    private final TextGraphRenderer textRenderer;
    private final SvgGraphRenderer svgRenderer;

    public CartographerRunner(CartographerProperties properties, GpxRouteLoader routeLoader,
                              IntersectionFinder intersectionFinder, GraphBuilder graphBuilder,
                              TextGraphRenderer textRenderer, SvgGraphRenderer svgRenderer) {
        this.properties = properties;
        this.routeLoader = routeLoader;
        this.intersectionFinder = intersectionFinder;
        this.graphBuilder = graphBuilder;
        this.textRenderer = textRenderer;
        this.svgRenderer = svgRenderer;
    }

    public void run(String[] args) throws IOException {
        List<Route> routes = loadRoutes(args);

        List<Intersection> intersections =
                intersectionFinder.findWithSelfIntersections(routes);
        RouteGraph graph = graphBuilder.build(routes, intersections);
        RouteGraph simplified = graphBuilder.simplify(graph);

        System.out.println();
        printGraphStats(graph, "Raw graph");
        printGraphStats(simplified, "Simplified graph");
        System.out.println();
        System.out.print(textRenderer.render(simplified));

        Path svgOutput = properties.svgOutput();
        Files.writeString(svgOutput, svgRenderer.render(simplified), StandardCharsets.UTF_8);
        System.out.println("\nSVG map written to " + svgOutput.toAbsolutePath());
    }

    private List<Route> loadRoutes(String[] args) throws IOException {
        List<Path> gpxFiles = resolveGpxFiles(args);
        // one parse per file: XML builders are single-threaded, so parsing
        // maps cleanly onto parallel tasks (encounter order is preserved)
        List<Route> routes = gpxFiles.parallelStream().map(this::loadRoute).toList();
        for (int i = 0; i < routes.size(); i++) {
            Route route = routes.get(i);
            Node start = route.getNodes().get(0);
            System.out.printf("%s: %d nodes, starting at (%.6f, %.6f)%n",
                    gpxFiles.get(i).getFileName(), route.getNodes().size(),
                    start.getLatitude(), start.getLongitude());
        }
        return routes;
    }

    private Route loadRoute(Path gpxFile) {
        try {
            return routeLoader.load(gpxFile);
        } catch (IOException ioException) {
            throw new UncheckedIOException("failed to parse " + gpxFile, ioException);
        }
    }

    private List<Path> resolveGpxFiles(String[] args) throws IOException {
        if (args.length > 0) {
            return Stream.of(args).map(Path::of).toList();
        }
        try (Stream<Path> files = Files.list(properties.routesDir())) {
            return files.filter(f -> f.getFileName().toString().endsWith(".gpx"))
                    .sorted()
                    .toList();
        }
    }

    private void printGraphStats(RouteGraph graph, String label) {
        long sharedEdges = graph.getEdges().stream().filter(GraphEdge::isShared).count();
        System.out.printf("%s: %d vertices (%d junctions, %d endpoints), %d edges (%d shared), %.2f km total%n",
                label,
                graph.getNodes().size(),
                graph.getJunctions().size(),
                graph.getNodes().size() - graph.getJunctions().size(),
                graph.getEdges().size(),
                sharedEdges,
                graph.totalLengthMeters() / 1000.0);
    }
}
