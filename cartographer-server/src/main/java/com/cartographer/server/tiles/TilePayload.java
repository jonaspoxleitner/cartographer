package com.cartographer.server.tiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One tile's bytes in both forms it is served in, plus the strong ETag that
 * identifies them.
 *
 * <p>Gzipping and hashing happen once, when the payload is created: a tile's
 * bytes only change when the graph is rebuilt, so the ETag — the SHA-256 of the
 * raw MVT bytes, quoted as RFC 9110 requires — is a stable identity for an
 * address until the next rebuild, and serving a payload never encodes it again.
 * The ETag covers the raw bytes, so the gzipped and the raw representation of a
 * tile share it; {@code Vary: Accept-Encoding} on the response keeps a cache
 * from handing one form to a client that asked for the other.
 */
public final class TilePayload {

    private static final String HASH_ALGORITHM = "SHA-256";

    private final byte[] raw;
    private final byte[] gzipped;
    private final String etag;

    private TilePayload(byte[] raw, byte[] gzipped, String etag) {
        this.raw = raw;
        this.gzipped = gzipped;
        this.etag = etag;
    }

    /** The payload for raw MVT bytes: gzipped once and hashed once. */
    public static TilePayload of(byte[] raw) {
        return new TilePayload(raw, gzip(raw), etag(raw));
    }

    /**
     * The payload for tile bytes that are already gzipped, as the MBTiles store
     * keeps them: decompressed for the raw form and hashed, never re-compressed,
     * so a restored tile is the byte-identical payload the pre-render produced.
     */
    public static TilePayload ofGzipped(byte[] gzipped) {
        byte[] raw = gunzip(gzipped);
        return new TilePayload(raw, gzipped, etag(raw));
    }

    /** The MVT bytes as built, for clients that do not accept gzip. */
    public byte[] raw() {
        return raw;
    }

    /** The MVT bytes gzipped, for {@code Content-Encoding: gzip}. */
    public byte[] gzipped() {
        return gzipped;
    }

    /** The strong ETag of {@link #raw()}, quotes included. */
    public String etag() {
        return etag;
    }

    private static byte[] gzip(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length / 2 + 32);
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        } catch (IOException ioException) {
            throw new UncheckedIOException("failed to gzip a tile", ioException);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] gzipped) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return gzip.readAllBytes();
        } catch (IOException ioException) {
            throw new UncheckedIOException("failed to gunzip a stored tile", ioException);
        }
    }

    private static String etag(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(HASH_ALGORITHM + " is not available", missing);
        }
        return "\"" + HexFormat.of().formatHex(digest.digest(bytes)) + "\"";
    }
}
