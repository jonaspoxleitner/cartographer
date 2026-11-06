package com.cartographer.server.tiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * under the old one. Nothing else is in it: a restart that changes none of
 * these can reuse the tiles it rendered last time.
 *
 * <p>The result is a lowercase hex SHA-256 over the files in sorted order, so
 * the same directory always yields the same fingerprint regardless of how the
 * file system lists it.
 */
public final class RoutesFingerprint {

    private static final String HASH_ALGORITHM = "SHA-256";

    /** Terminates each field fed into the digest, so a name ending in digits cannot merge with a size. */
    private static final byte FIELD_SEPARATOR = 0;

    private RoutesFingerprint() {
    }

    /**
     * The fingerprint of the tiles rendered from {@code gpxFiles} with these
     * settings.
     *
     * @param gpxFiles the route files the graph was built from, in any order
     * @param intersectionToleranceMeters the distance within which two tracks cross, as the graph was built
     * @param tileBufferUnits the clipping buffer the tiles were rendered with
     * @param minZoom lowest pre-rendered zoom level
     * @param maxZoom highest pre-rendered zoom level
     */
    public static String of(List<Path> gpxFiles, double intersectionToleranceMeters, int tileBufferUnits,
                            int minZoom, int maxZoom) {
        MessageDigest digest = sha256();
        gpxFiles.stream()
                .sorted()
                .forEach(file -> updateWithFile(digest, file));
        updateWith(digest, "intersection-tolerance-meters=" + intersectionToleranceMeters);
        updateWith(digest, "tile-buffer-units=" + tileBufferUnits);
        updateWith(digest, "tile-cache-min-zoom=" + minZoom);
        updateWith(digest, "tile-cache-max-zoom=" + maxZoom);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateWithFile(MessageDigest digest, Path file) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException ioException) {
            throw new UncheckedIOException("failed to read " + file.toAbsolutePath(), ioException);
        }
        updateWith(digest, file.getFileName().toString());
        updateWith(digest, Long.toString(attributes.size()));
        updateWith(digest, Long.toString(attributes.lastModifiedTime().toMillis()));
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
