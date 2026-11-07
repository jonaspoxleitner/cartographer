package com.cartographer.server.tiles;

import com.cartographer.map.GraphBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The identity of a tile snapshot's inputs: which GPX files the graph was built
 * from, and which settings its tiles were rendered with.
 *
 * <p>Every file contributes its name, size and modification time, so adding,
 * removing, editing or re-uploading a route changes the fingerprint. The
 * settings that decide the bytes of an address — the intersection tolerance
 * that decides which edges exist, the clipping buffer and the zoom range — are
 * part of it too, so a changed setting cannot be answered with tiles rendered
 * under the old one. The junction circle radius decides where an edge ends at
 * its junction, and it is a code constant rather than a setting, so it is fed
 * in from {@link GraphBuilder} directly. The tile-format version stands in for
 * the encoder itself: bumping it says "the same routes now render to different
 * bytes", which no setting expresses. Nothing else is in it: a restart that
 * changes none of these can reuse the tiles it rendered last time.
 *
 * <p>The result is a lowercase hex SHA-256 over the files in sorted order, so
 * the same directory always yields the same fingerprint regardless of how the
 * file system lists it.
 */
public final class RoutesFingerprint {

    private static final String HASH_ALGORITHM = "SHA-256";

    /**
     * Version of the tile content this build renders. Bump it whenever a change
     * makes stored tiles stale without changing any input: geometry the encoder
     * writes, layers it emits, property values on a feature. Tiles rendered under
     * another version fail the fingerprint and are rendered again.
     *
     * <p>2 — edges cut at the junction circle with a spoke to the junction
     * coordinate, plus the {@code junctions} point layer.
     */
    static final int TILE_FORMAT_VERSION = 2;

    /** Terminates each field fed into the digest, so a name ending in digits cannot merge with a size. */
    private static final byte FIELD_SEPARATOR = 0;

    private RoutesFingerprint() {
    }

    /**
     * The fingerprint of the tiles rendered from {@code gpxFiles} with these
     * settings, under the current {@link #TILE_FORMAT_VERSION}.
     *
     * @param gpxFiles the route files the graph was built from, in any order
     * @param intersectionToleranceMeters the distance within which two tracks cross, as the graph was built
     * @param tileBufferUnits the clipping buffer the tiles were rendered with
     * @param minZoom lowest pre-rendered zoom level
     * @param maxZoom highest pre-rendered zoom level
     */
    public static String of(List<Path> gpxFiles, double intersectionToleranceMeters, int tileBufferUnits,
                            int minZoom, int maxZoom) {
        return of(gpxFiles, intersectionToleranceMeters, tileBufferUnits, minZoom, maxZoom, TILE_FORMAT_VERSION);
    }

    /**
     * The fingerprint under a given tile-format version. The public
     * {@link #of} always passes {@link #TILE_FORMAT_VERSION}; this overload is how
     * a test shows that another version no longer matches.
     */
    static String of(List<Path> gpxFiles, double intersectionToleranceMeters, int tileBufferUnits,
                     int minZoom, int maxZoom, int tileFormatVersion) {
        return digest(fields(gpxFiles, intersectionToleranceMeters, tileBufferUnits, minZoom, maxZoom,
                tileFormatVersion));
    }

    /**
     * The fields the digest is computed over, in order. Exposed to tests so that
     * a field's presence can be asserted without guessing at a hash.
     */
    static List<String> fields(List<Path> gpxFiles, double intersectionToleranceMeters, int tileBufferUnits,
                               int minZoom, int maxZoom, int tileFormatVersion) {
        List<String> fields = new ArrayList<>();
        gpxFiles.stream()
                .sorted()
                .forEach(file -> addFileFields(fields, file));
        fields.add("intersection-tolerance-meters=" + intersectionToleranceMeters);
        fields.add("junction-circle-radius-meters=" + GraphBuilder.JUNCTION_CIRCLE_RADIUS_METERS);
        fields.add("tile-buffer-units=" + tileBufferUnits);
        fields.add("tile-cache-min-zoom=" + minZoom);
        fields.add("tile-cache-max-zoom=" + maxZoom);
        fields.add("tile-format-version=" + tileFormatVersion);
        return fields;
    }

    private static String digest(List<String> fields) {
        MessageDigest digest = sha256();
        fields.forEach(field -> updateWith(digest, field));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void addFileFields(List<String> fields, Path file) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException ioException) {
            throw new UncheckedIOException("failed to read " + file.toAbsolutePath(), ioException);
        }
        fields.add(file.getFileName().toString());
        fields.add(Long.toString(attributes.size()));
        fields.add(Long.toString(attributes.lastModifiedTime().toMillis()));
    }

    private static void updateWith(MessageDigest digest, String field) {
        digest.update(field.getBytes(StandardCharsets.UTF_8));
        digest.update(FIELD_SEPARATOR);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(HASH_ALGORITHM + " is not available", missing);
        }
    }
}
