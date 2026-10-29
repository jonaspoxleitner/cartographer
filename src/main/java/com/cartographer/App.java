package com.cartographer;

import com.cartographer.io.GpxRouteLoader;
import com.cartographer.model.Node;
import com.cartographer.model.Route;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class App {

    public static void main(String[] args) {
        try {
            for (Path gpxFile : resolveGpxFiles(args)) {
                printRouteInfo(gpxFile);
            }
        } catch (IOException e) {
            System.err.println("Could not load routes: " + e.getMessage());
            System.exit(1);
        }
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

    private static void printRouteInfo(Path gpxFile) throws IOException {
        Route route = new GpxRouteLoader().load(gpxFile);
        Node start = route.getNodes().isEmpty() ? null : route.getNodes().get(0);

        System.out.printf("%s: %d nodes, starting at (%.6f, %.6f)%n",
                gpxFile.getFileName(),
                route.getNodes().size(),
                start != null ? start.getLatitude() : Double.NaN,
                start != null ? start.getLongitude() : Double.NaN);
    }
}
