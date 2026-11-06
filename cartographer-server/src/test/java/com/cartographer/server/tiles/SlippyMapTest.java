package com.cartographer.server.tiles;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the slippy-map math: Web Mercator normalisation, tile addressing and
 * tile-local integer coordinates at extent 4096 with y growing southward.
 */
class SlippyMapTest {

    private static final double EPSILON = 1e-9;

    @Test
    void projectsWorldIntoNormalisedMercatorSquare() {
        assertEquals(0.0, SlippyMap.mercatorX(-180.0), EPSILON);
        assertEquals(0.5, SlippyMap.mercatorX(0.0), EPSILON);
        assertEquals(1.0, SlippyMap.mercatorX(180.0), EPSILON);

        assertEquals(0.5, SlippyMap.mercatorY(0.0), EPSILON);
        assertEquals(0.0, SlippyMap.mercatorY(85.05112877980659), EPSILON);
        assertEquals(1.0, SlippyMap.mercatorY(-85.05112877980659), EPSILON);
    }

    @Test
    void zoomZeroIsTheWorldTile() {
        assertEquals(1, SlippyMap.tilesPerAxis(0));
        assertEquals(0, SlippyMap.tileX(13.4, 0));
        assertEquals(0, SlippyMap.tileY(52.5, 0));
        assertEquals(2200, SlippyMap.localX(13.4, 0, 0));
        assertEquals(1344, SlippyMap.localY(52.5, 0, 0));
    }

    @Test
    void knownCoordinateAddressesKnownTile() {
        // 52.5°N 13.4°E (Berlin) at zoom 13: column 4400, row 2687.
        assertEquals(4400, SlippyMap.tileX(13.4, 13));
        assertEquals(2687, SlippyMap.tileY(52.5, 13));
        assertEquals(3787, SlippyMap.localX(13.4, 4400, 13));
        assertEquals(1460, SlippyMap.localY(52.5, 2687, 13));
    }

    @Test
    void equatorAndPrimeMeridianAreTileOriginAtZoomOne() {
        // (0,0) sits exactly on the corner shared by all four z=1 tiles.
        assertEquals(1, SlippyMap.tileX(0.0, 1));
        assertEquals(1, SlippyMap.tileY(0.0, 1));
        assertEquals(0, SlippyMap.localX(0.0, 1, 1));
        assertEquals(0, SlippyMap.localY(0.0, 1, 1));

        assertEquals(8192, SlippyMap.tileX(0.0, 14));
        assertEquals(8192, SlippyMap.tileY(0.0, 14));
        assertEquals(0, SlippyMap.localX(0.0, 8192, 14));
        assertEquals(0, SlippyMap.localY(0.0, 8192, 14));
    }

    @Test
    void tileCornersMapToExtentCorners() {
        // At z=1 the tile (1,1) is the south-east quadrant: its north-west corner
        // is (0°E, 0°N) and its south-east corner is (180°E, -85.0511°N).
        double northWestLongitude = SlippyMap.longitudeForTileX(1, 1);
        double northWestLatitude = SlippyMap.latitudeForTileY(1, 1);
        assertEquals(0.0, northWestLongitude, EPSILON);
        assertEquals(0.0, northWestLatitude, EPSILON);
        assertEquals(0, SlippyMap.localX(northWestLongitude, 1, 1));
        assertEquals(0, SlippyMap.localY(northWestLatitude, 1, 1));

        double southEastLongitude = SlippyMap.longitudeForTileX(2, 1);
        double southEastLatitude = SlippyMap.latitudeForTileY(2, 1);
        assertEquals(180.0, southEastLongitude, EPSILON);
        assertEquals(-85.05112877980659, southEastLatitude, 1e-6);
        assertEquals(SlippyMap.TILE_EXTENT, SlippyMap.localX(southEastLongitude, 1, 1));
        assertEquals(SlippyMap.TILE_EXTENT, SlippyMap.localY(southEastLatitude, 1, 1));
    }

    @Test
    void localYIncreasesSouthward() {
        // Both latitudes fall into tile row 2687 at zoom 13.
        int northern = SlippyMap.localY(52.505, SlippyMap.tileY(52.505, 13), 13);
        int southern = SlippyMap.localY(52.49, SlippyMap.tileY(52.49, 13), 13);
        assertEquals(694, northern);
        assertEquals(2991, southern);
        assertTrue(northern < southern, northern + " should be north of " + southern);
    }

    @Test
    void polesAndAntimeridianAreClampedIntoTheTileGrid() {
        assertEquals(0, SlippyMap.tileY(90.0, 1));
        assertEquals(0, SlippyMap.localY(90.0, 0, 1));
        assertEquals(1, SlippyMap.tileY(-90.0, 1));
        assertEquals(SlippyMap.TILE_EXTENT, SlippyMap.localY(-90.0, 1, 1));
        assertEquals(1, SlippyMap.tileX(180.0, 1));
        assertEquals(SlippyMap.TILE_EXTENT, SlippyMap.localX(180.0, 1, 1));
        assertEquals(85.05112877980659, SlippyMap.latitudeForTileY(0, 1), 1e-6);
    }

    @Test
    void rejectsZoomOutsideSupportedRange() {
        assertThrows(IllegalArgumentException.class, () -> SlippyMap.tilesPerAxis(-1));
        assertThrows(IllegalArgumentException.class, () -> SlippyMap.tilesPerAxis(SlippyMap.MAX_ZOOM + 1));
        assertEquals(4_194_304, SlippyMap.tilesPerAxis(SlippyMap.MAX_ZOOM));
    }
}
