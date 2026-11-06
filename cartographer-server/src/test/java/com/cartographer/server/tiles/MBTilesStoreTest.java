package com.cartographer.server.tiles;

import com.cartographer.server.tiles.TileCache.TileAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MBTiles boundary: a written snapshot reads back under the same XYZ
 * addresses with the same bytes and ETag, the two spec-mandated conversions are
 * visible in the file itself (gzipped {@code tile_data}, rows counted from the
 * south), and anything but a store written for the same fingerprint reads as
 * absent.
 *
 * <p>The fixture bytes are not valid MVT — the store never looks inside them —
 * and the two addresses share neither zoom nor row, so a wrong y-flip or a lost
 * zoom would show up as a missing address rather than a passing test.
 */
class MBTilesStoreTest {

    private static final String FINGERPRINT = "fingerprint-a";
    private static final byte[] TILE_AT_ZOOM_14 = "tile bytes at zoom 14".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TILE_AT_ZOOM_2 = "tile bytes at zoom 2".getBytes(StandardCharsets.UTF_8);
    private static final TileAddress ADDRESS_AT_ZOOM_14 = new TileAddress(14, 8819, 5654);
    private static final TileAddress ADDRESS_AT_ZOOM_2 = new TileAddress(2, 1, 0);

    @TempDir
    Path tempDir;

    @Test
    void readReturnsTheTilesThatWereWritten() {
        MBTilesStore store = store();

        store.write(FINGERPRINT, Map.of(ADDRESS_AT_ZOOM_14, TilePayload.of(TILE_AT_ZOOM_14),
                ADDRESS_AT_ZOOM_2, TilePayload.of(TILE_AT_ZOOM_2)));
        Map<TileAddress, TilePayload> restored = store.read(FINGERPRINT).orElseThrow();

        assertEquals(2, restored.size());
        assertPayloadEquals(TilePayload.of(TILE_AT_ZOOM_14), restored.get(ADDRESS_AT_ZOOM_14));
        assertPayloadEquals(TilePayload.of(TILE_AT_ZOOM_2), restored.get(ADDRESS_AT_ZOOM_2));
    }

    @Test
    void tilesAreStoredGzippedAndWithRowsCountedFromTheSouth() throws SQLException {
        MBTilesStore store = store();
        store.write(FINGERPRINT, Map.of(ADDRESS_AT_ZOOM_14, TilePayload.of(TILE_AT_ZOOM_14)));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + store.file().toAbsolutePath());
             Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles")) {
            assertTrue(rows.next(), "the written tile is missing from the file");
            assertEquals(14, rows.getInt("zoom_level"));
            assertEquals(ADDRESS_AT_ZOOM_14.x(), rows.getInt("tile_column"));
            // TMS: the row counts up from the south edge, MVT's y counts down from the north
            assertEquals(SlippyMap.tilesPerAxis(14) - 1 - ADDRESS_AT_ZOOM_14.y(), rows.getInt("tile_row"));
            assertArrayEquals(TILE_AT_ZOOM_14, gunzip(rows.getBytes("tile_data")), "tile_data must be gzipped");
            assertFalse(rows.next(), "exactly one tile was written");
        }
    }

    @Test
    void writeReplacesThePreviousSnapshot() {
        MBTilesStore store = store();
        store.write(FINGERPRINT, Map.of(ADDRESS_AT_ZOOM_2, TilePayload.of(TILE_AT_ZOOM_2)));

        store.write("fingerprint-b", Map.of(ADDRESS_AT_ZOOM_14, TilePayload.of(TILE_AT_ZOOM_14)));

        Map<TileAddress, TilePayload> restored = store.read("fingerprint-b").orElseThrow();
        assertEquals(Set.of(ADDRESS_AT_ZOOM_14), restored.keySet(), "the old tile must not survive the rewrite");
    }

    @Test
    void readOfAnotherFingerprintIsAbsent() {
        MBTilesStore store = store();
        store.write(FINGERPRINT, Map.of(ADDRESS_AT_ZOOM_14, TilePayload.of(TILE_AT_ZOOM_14)));

        assertTrue(store.read("fingerprint-b").isEmpty());
    }

    @Test
    void writeReplacesAFileThatIsNotAStore() throws IOException {
        MBTilesStore store = store();
        Files.writeString(store.file(), "not a database");

        store.write(FINGERPRINT, Map.of(ADDRESS_AT_ZOOM_2, TilePayload.of(TILE_AT_ZOOM_2)));

        // a cache that is being rewritten must not stay broken because of the file it replaces
        assertEquals(Set.of(ADDRESS_AT_ZOOM_2), store.read(FINGERPRINT).orElseThrow().keySet());
    }

    @Test
    void readWithoutAFileIsAbsent() {
        assertTrue(store().read(FINGERPRINT).isEmpty());
    }

    @Test
    void readOfAFileThatIsNotAStoreIsAbsent() throws IOException {
        MBTilesStore store = store();
        Files.writeString(store.file(), "not a database");

        assertTrue(store.read(FINGERPRINT).isEmpty());
    }

    private MBTilesStore store() {
        return new MBTilesStore(tempDir.resolve("tiles.mbtiles"));
    }

    private static void assertPayloadEquals(TilePayload expected, TilePayload actual) {
        assertArrayEquals(expected.raw(), actual.raw());
        assertArrayEquals(expected.gzipped(), actual.gzipped());
        assertEquals(expected.etag(), actual.etag());
    }

    private static byte[] gunzip(byte[] gzipped) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return in.readAllBytes();
        } catch (IOException ioException) {
            throw new UncheckedIOException("stored tile_data is not gzip", ioException);
        }
    }
}
