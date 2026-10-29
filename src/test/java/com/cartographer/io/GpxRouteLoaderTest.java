package com.cartographer.io;

import com.cartographer.model.Route;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GpxRouteLoaderTest {

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

    @Test
    void parsesTrackPointsAndWaypoints() throws Exception {
        Route route = new GpxRouteLoader().load(
                new ByteArrayInputStream(MINIMAL_GPX.getBytes(StandardCharsets.UTF_8)));

        assertEquals("Test Route", route.getName());
        assertEquals(1, route.getWaypoints().size());
        assertEquals("Start", route.getWaypoints().get(0).getName());
        assertEquals(3, route.getNodes().size());
        assertEquals(715.9, route.getNodes().get(0).getElevation(), 0.001);
        assertTrue(route.totalDistanceMeters() > 0);
    }

    @Test
    void loadsSampleRouteFromDisk() throws Exception {
        Route route = new GpxRouteLoader().load(Path.of("routes", "komoot-4321335.gpx"));

        assertNotNull(route.getName());
        assertEquals(7, route.getWaypoints().size());
        assertTrue(route.getNodes().size() > 100);
    }

    @Test
    void missingFileThrows() {
        assertThrows(Exception.class,
                () -> new GpxRouteLoader().load(Path.of("routes", "does-not-exist.gpx")));
    }
}
