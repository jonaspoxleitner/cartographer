package com.cartographer.server.tiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The fingerprint decides whether a start reuses the stored tiles or renders
 * them again, so it must be stable for unchanged inputs and must change with
 * every setting that decides what the tiles contain — including the
 * intersection tolerance, which decides which edges exist at all.
 */
class RoutesFingerprintTest {

    private static final double TOLERANCE_METERS = 10.0;
    private static final int BUFFER_UNITS = 64;
    private static final int MIN_ZOOM = 0;
    private static final int MAX_ZOOM = 18;

    @TempDir
    Path tempDir;

    @Test
    void unchangedFilesAndSettingsGiveTheSameFingerprint() throws IOException {
        List<Path> files = routeFiles();

        String fingerprint = fingerprintOf(files, TOLERANCE_METERS, BUFFER_UNITS, MIN_ZOOM, MAX_ZOOM);

        assertEquals(fingerprint, fingerprintOf(files, TOLERANCE_METERS, BUFFER_UNITS, MIN_ZOOM, MAX_ZOOM));
        assertEquals(fingerprint, fingerprintOf(files.reversed(), TOLERANCE_METERS, BUFFER_UNITS, MIN_ZOOM,
                MAX_ZOOM), "the order the directory lists the files in must not matter");
    }

    @Test
    void everySettingThatDecidesTheTilesChangesTheFingerprint() throws IOException {
        List<Path> files = routeFiles();
        String fingerprint = fingerprintOf(files, TOLERANCE_METERS, BUFFER_UNITS, MIN_ZOOM, MAX_ZOOM);

        assertNotEquals(fingerprint, fingerprintOf(files, TOLERANCE_METERS + 1.0, BUFFER_UNITS, MIN_ZOOM, MAX_ZOOM),
                "a changed intersection tolerance finds other edges, so stored tiles are stale");
        assertNotEquals(fingerprint, fingerprintOf(files, TOLERANCE_METERS, BUFFER_UNITS + 1, MIN_ZOOM, MAX_ZOOM));
        assertNotEquals(fingerprint, fingerprintOf(files, TOLERANCE_METERS, BUFFER_UNITS, MIN_ZOOM + 1, MAX_ZOOM));
        assertNotEquals(fingerprint, fingerprintOf(files, TOLERANCE_METERS, BUFFER_UNITS, MIN_ZOOM, MAX_ZOOM - 1));
    }

    private List<Path> routeFiles() throws IOException {
        Path first = Files.writeString(tempDir.resolve("route-a.gpx"), "<gpx/>");
        Path second = Files.writeString(tempDir.resolve("route-b.gpx"), "<gpx/>");
        return List.of(first, second);
    }

    private static String fingerprintOf(List<Path> files, double toleranceMeters, int bufferUnits, int minZoom,
                                        int maxZoom) {
        return RoutesFingerprint.of(files, toleranceMeters, bufferUnits, minZoom, maxZoom);
    }
}
