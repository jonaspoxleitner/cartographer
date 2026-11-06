package com.cartographer.server.tiles;

/**
 * Slippy map (XYZ) tile addressing and the Web Mercator projection behind it.
 *
 * <p>Longitudes and latitudes (EPSG:4326) are projected to normalised Web
 * Mercator coordinates in {@code [0,1]}, split into {@code 2^zoom} tiles per
 * axis, and finally expressed as integer tile-local coordinates in
 * {@code 0..}{@value #TILE_EXTENT} for the MVT extent. Tile-local y grows
 * southward (0 at the tile's north edge, {@code TILE_EXTENT} at its south edge),
 * matching MVT's screen-style coordinate system.
 */
public final class SlippyMap {

    /** MVT layer extent: tile-local coordinates span {@code 0..4096}. */
    public static final int TILE_EXTENT = 4096;

    /** Highest zoom level the tile endpoints accept; also bounds the address math. */
    public static final int MAX_ZOOM = 22;

    /** Latitude at which the square Web Mercator projection ends (the world's top edge). */
    private static final double MERCATOR_LIMIT_LATITUDE = 85.05112877980659;

    private SlippyMap() {
    }

    /** Number of tiles per axis at {@code zoom}: {@code 2^zoom}. */
    public static int tilesPerAxis(int zoom) {
        if (zoom < 0 || zoom > MAX_ZOOM) {
            throw new IllegalArgumentException("zoom out of range 0.." + MAX_ZOOM + ": " + zoom);
        }
        return 1 << zoom;
    }

    /** Normalised Web Mercator x in {@code [0,1]}: 0 at 180°W, 0.5 at the prime meridian. */
    public static double mercatorX(double longitude) {
        return (longitude + 180.0) / 360.0;
    }

    /**
     * Normalised Web Mercator y in {@code [0,1]}: 0 at the world's north edge,
     * 0.5 at the equator. Latitudes beyond the Mercator limit are clamped.
     */
    public static double mercatorY(double latitude) {
        double clipped = Math.max(-MERCATOR_LIMIT_LATITUDE, Math.min(MERCATOR_LIMIT_LATITUDE, latitude));
        double radians = Math.toRadians(clipped);
        double mercator = (1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI) / 2;
        return Math.max(0.0, Math.min(1.0, mercator));
    }

    /** Tile column (x) containing the longitude; clamped to the valid range at the antimeridian. */
    public static int tileX(double longitude, int zoom) {
        int tiles = tilesPerAxis(zoom);
        return clampTile((int) Math.floor(mercatorX(longitude) * tiles), tiles);
    }

    /** Tile row (y) containing the latitude; clamped to the valid range at the poles. */
    public static int tileY(double latitude, int zoom) {
        int tiles = tilesPerAxis(zoom);
        return clampTile((int) Math.floor(mercatorY(latitude) * tiles), tiles);
    }

    /**
     * Tile-local x within tile column {@code tileX}: 0 at the tile's west edge,
     * {@value #TILE_EXTENT} at its east edge. Points outside the tile are
     * clamped, so the caller must pick the tile that contains the longitude.
     */
    public static int localX(double longitude, int tileX, int zoom) {
        double fraction = mercatorX(longitude) * tilesPerAxis(zoom) - tileX;
        return clampLocal(Math.round(fraction * TILE_EXTENT));
    }

    /**
     * Tile-local y within tile row {@code tileY}: 0 at the tile's north edge,
     * {@value #TILE_EXTENT} at its south edge. Points outside the tile are
     * clamped, so the caller must pick the tile that contains the latitude.
     */
    public static int localY(double latitude, int tileY, int zoom) {
        double fraction = mercatorY(latitude) * tilesPerAxis(zoom) - tileY;
        return clampLocal(Math.round(fraction * TILE_EXTENT));
    }

    /** Longitude of the vertical tile edge at fractional column {@code tileX}: 0 → 180°W. */
    public static double longitudeForTileX(double tileX, int zoom) {
        return tileX / tilesPerAxis(zoom) * 360.0 - 180.0;
    }

    /** Latitude of the horizontal tile edge at fractional row {@code tileY}: 0 → the north edge. */
    public static double latitudeForTileY(double tileY, int zoom) {
        double mercator = Math.PI * (1 - 2 * tileY / tilesPerAxis(zoom));
        return Math.toDegrees(Math.atan(Math.sinh(mercator)));
    }

    private static int clampTile(int tile, int tiles) {
        return Math.max(0, Math.min(tiles - 1, tile));
    }

    private static int clampLocal(long local) {
        return (int) Math.max(0, Math.min(TILE_EXTENT, local));
    }
}
