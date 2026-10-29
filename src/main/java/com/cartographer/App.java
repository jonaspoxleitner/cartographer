package com.cartographer;

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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class App {

    private static final double INTERSECTION_TOLERANCE_METERS = 10.0;
    private static final Path SVG_OUTPUT = Path.of("graph.svg");

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IOException e) {
            System.err.println("Could not load routes: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        List<Route> routes = loadRoutes(args);

        List<Intersection> intersections =
                new IntersectionFinder(INTERSECTION_TOLERANCE_METERS).findWithSelfIntersections(routes);
        GraphBuilder builder = new GraphBuilder(INTERSECTION_TOLERANCE_METERS);
        RouteGraph graph = builder.build(routes, intersections);
        RouteGraph simplified = builder.simplify(graph);

        System.out.println();
        printGraphStats(graph, "Raw graph");
        printGraphStats(simplified, "Simplified graph");
        System.out.println();
        System.out.print(new TextGraphRenderer().render(simplified));

        Files.writeString(SVG_OUTPUT, new SvgGraphRenderer().render(simplified),
                StandardCharsets.UTF_8);
        System.out.println("\nSVG map written to " + SVG_OUTPUT.toAbsolutePath());
    }

    private static List<Route> loadRoutes(String[] args) throws IOException {
        GpxRouteLoader loader = new GpxRouteLoader();
        List<Route> routes = new ArrayList<>();
        for (Path gpxFile : resolveGpxFiles(args)) {
            routes.add(loader.load(gpxFile));
            Node start = routes.get(routes.size() - 1).getNodes().get(0);
            System.out.printf("%s: %d nodes, starting at (%.6f, %.6f)%n",
                    gpxFile.getFileName(),
                    routes.get(routes.size() - 1).getNodes().size(),
                    start.getLatitude(), start.getLongitude());
        }
        return routes;
    }

    private static List<Path> resolveGpxFiles(String[] args) throws IOException {
        if (args.length > 0) {
            return Stream.of(args).map(Path::of).toList();
        }
        try (Stream<Path> files = Files.list(Path.of("routes"))) {
            return files.filter(f -> f.getFileName().toString().endsWith(".gpx"))
                    .sorted()
                    .toList();
        }
    }

    private static void printGraphStats(RouteGraph graph, String label) {
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
