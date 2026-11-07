package com.cartographer.io;

import com.cartographer.model.Route;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class GpxRouteLoaderTest {

    // GPX samples live at the repository root, while surefire runs with the
    // module as working directory. Fall back to "." so IDE runs from the root work.
    private static final Path ROUTES_DIR = Path.of(
            System.getProperty("maven.multiModuleProjectDirectory", "."), "routes");

    private static final String MINIMAL_GPX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>Test Route</name></metadata>
              <wpt lat="48.601275" lon="13.787572">
                <name>Start</name>
              </wpt>
              <trk>
                <name>Test Route</name>
                <trkseg>
                  <trkpt lat="48.601275" lon="13.787572"><ele>715.9</ele></trkpt>
                  <trkpt lat="48.601264" lon="13.787481"><ele>715.9</ele></trkpt>
                  <trkpt lat="48.601335" lon="13.786848"><ele>720.0</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """;

    private static final String TIMESTAMPED_GPX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="48.601275" lon="13.787572">
                <time>2024-12-24T12:03:37Z</time>
                <name>Start</name>
              </wpt>
              <trk>
                <name>Test Route</name>
                <trkseg>
                  <trkpt lat="48.601275" lon="13.787572">
                    <ele>715.9</ele>
                    <time>2024-12-24T12:03:37Z</time>
                  </trkpt>
                  <trkpt lat="48.601264" lon="13.787481">
                    <ele>715.9</ele>
                    <time>2024-12-24T12:03:38Z</time>
                  </trkpt>
                  <trkpt lat="48.601335" lon="13.786848">
                    <ele>720.0</ele>
                    <time>2024-12-24T12:03:39.500Z</time>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
            """;

    @Test
    void parsesTrackPointsAndWaypoints() throws Exception {
        Route route = new GpxRouteLoader().load(
                new ByteArrayInputStream(MINIMAL_GPX.getBytes(StandardCharsets.UTF_8)));

        assertEquals("Test Route", route.getName());
        assertEquals(1, route.getWaypoints().size());
        assertEquals("Start", route.getWaypoints().get(0).getName());
        assertEquals(3, route.getNodes().size());
        assertEquals(715.9, route.getNodes().get(0).getElevation(), 0.001);
    }

    @Test
    void parsesTimestampsOnTrackPointsAndWaypoints() throws Exception {
        Route route = new GpxRouteLoader().load(
                new ByteArrayInputStream(TIMESTAMPED_GPX.getBytes(StandardCharsets.UTF_8)));

        assertEquals(Instant.parse("2024-12-24T12:03:37Z"), route.getWaypoints().get(0).getTime());
        assertEquals(Instant.parse("2024-12-24T12:03:37Z"), route.getNodes().get(0).getTime());
        assertEquals(Instant.parse("2024-12-24T12:03:38Z"), route.getNodes().get(1).getTime());
        assertEquals(Instant.parse("2024-12-24T12:03:39.500Z"), route.getNodes().get(2).getTime());
    }

    @Test
    void nodesWithoutTimeLoadWithNullTime() throws Exception {
        Route route = new GpxRouteLoader().load(
                new ByteArrayInputStream(MINIMAL_GPX.getBytes(StandardCharsets.UTF_8)));

        assertEquals(3, route.getNodes().size());
        route.getNodes().forEach(node -> assertNull(node.getTime()));
        assertNull(route.getWaypoints().get(0).getTime());
    }

    @Test
    void loadsSampleRouteFromDisk() throws Exception {
        Route route = new GpxRouteLoader().load(ROUTES_DIR.resolve("komoot-4321335.gpx"));

        assertNotNull(route.getName());
        assertEquals(7, route.getWaypoints().size());
        assertTrue(route.getNodes().size() > 100);
        assertNotNull(route.getNodes().get(0).getTime());
    }

    @Test
    void missingFileThrows() {
        assertThrows(Exception.class,
                () -> new GpxRouteLoader().load(ROUTES_DIR.resolve("does-not-exist.gpx")));
    }
}
