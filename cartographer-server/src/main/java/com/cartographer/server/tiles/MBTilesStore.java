package com.cartographer.server.tiles;

import com.cartographer.server.tiles.TileCache.TileAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The pre-rendered tiles as an MBTiles file: one SQLite database holding the
 * snapshot, plus the {@link RoutesFingerprint} of the inputs it was rendered
 * from.
 *
 * <p>The two things the MBTiles spec demands and nothing else in the server
 * does are confined to this class: {@code tile_data} is the gzipped MVT bytes,
 * and {@code tile_row} counts from the south edge (TMS), so the row written for
 * XYZ row {@code y} at zoom {@code z} is {@code 2^z - 1 - y}. Callers hand over
 * and receive {@link TilePayload}s under XYZ addresses.
 *
 * <p>A write replaces the whole file: it fills a sibling file in one transaction
 * and moves that over the store, so the store never holds a half-filled
 * snapshot, a failed write keeps the previous one, and a file that is not a
 * store at all is replaced rather than failing every later write. Failure is
 * only logged — the snapshot in memory is complete and the next start pays for
 * a re-render instead of the running server failing.
 *
 * <p>A read reports absent when there is no file, when the stored fingerprint is
 * for other inputs, or when the file cannot be read as a store. Every one of
 * those cases means the same thing to the caller: render the tiles again.
 */
public final class MBTilesStore {

    private static final Logger log = LoggerFactory.getLogger(MBTilesStore.class);

    private static final String JDBC_URL_PREFIX = "jdbc:sqlite:";

    /** Metadata key holding the {@link RoutesFingerprint} the tiles were rendered from. */
    private static final String FINGERPRINT_KEY = "fingerprint";

    /** MBTiles requires these two metadata entries; viewers key off them, the cache does not. */
    private static final String NAME_METADATA_KEY = "name";
    private static final String NAME_METADATA_VALUE = "cartographer route graph";
    private static final String FORMAT_METADATA_KEY = "format";
    private static final String FORMAT_METADATA_VALUE = "pbf";

    private static final String DROP_METADATA = "DROP TABLE IF EXISTS metadata";
    private static final String DROP_TILES = "DROP TABLE IF EXISTS tiles";
    private static final String CREATE_METADATA =
            "CREATE TABLE metadata (name TEXT NOT NULL, value TEXT NOT NULL)";
    private static final String INDEX_METADATA_NAME = "CREATE UNIQUE INDEX name ON metadata (name)";
    private static final String CREATE_TILES = "CREATE TABLE tiles (zoom_level INTEGER NOT NULL,"
            + " tile_column INTEGER NOT NULL, tile_row INTEGER NOT NULL, tile_data BLOB NOT NULL)";
    private static final String INDEX_TILE_ADDRESS =
            "CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)";
    private static final String INSERT_METADATA = "INSERT INTO metadata (name, value) VALUES (?, ?)";
    private static final String INSERT_TILE =
            "INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)";
    private static final String SELECT_METADATA = "SELECT value FROM metadata WHERE name = ?";
    private static final String SELECT_TILES = "SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles";

    private final Path file;

    /** @param file the MBTiles file to read and write; nothing is created until the first write */
    public MBTilesStore(Path file) {
        this.file = file;
    }

    /** The MBTiles file this store reads and writes. */
    public Path file() {
        return file;
    }

    /**
     * Replaces the file's content with {@code tiles}, stamped with
     * {@code fingerprint}. A write that fails is logged and leaves the previous
     * content in place: persistence is an optimisation, not a prerequisite for
     * serving tiles.
     */
    public void write(String fingerprint, Map<TileAddress, TilePayload> tiles) {
        long startedAtNanos = System.nanoTime();
        // built beside the store and moved over it, so a broken file cannot block the
        // rewrite that would repair it, and a failed write cannot damage a good one
        Path pending = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            fill(pending, fingerprint, tiles);
            Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (SQLException | IOException failed) {
            log.warn("failed to write {} tiles to {}: {}", tiles.size(), file.toAbsolutePath(), failed.toString());
            deleteQuietly(pending);
            return;
        }
        log.info("Wrote {} tiles to {} in {} ms", tiles.size(), file.toAbsolutePath(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
    }

    /**
     * The stored snapshot, when it was written for {@code fingerprint}; empty
     * when there is no store, it was written for other inputs, or it cannot be
     * read.
     */
    public Optional<Map<TileAddress, TilePayload>> read(String fingerprint) {
        if (!Files.isRegularFile(file)) {
            log.debug("no tile store at {}", file.toAbsolutePath());
            return Optional.empty();
        }
        try (Connection connection = connect(file)) {
            if (readMetadata(connection, FINGERPRINT_KEY).filter(fingerprint::equals).isEmpty()) {
                log.info("Tile store {} was rendered from other inputs; ignoring it", file.toAbsolutePath());
                return Optional.empty();
            }
            return Optional.of(readTiles(connection));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("ignoring unreadable tile store {}: {}", file.toAbsolutePath(), unreadable.toString());
            return Optional.empty();
        }
    }

    /** Fills {@code target} with a complete snapshot in one transaction. */
    private static void fill(Path target, String fingerprint, Map<TileAddress, TilePayload> tiles)
            throws SQLException {
        try (Connection connection = connect(target)) {
            connection.setAutoCommit(false);
            createSchema(connection);
            writeMetadata(connection, fingerprint);
            writeTiles(connection, tiles);
            connection.commit();
        }
    }

    private static Connection connect(Path target) throws SQLException {
        return DriverManager.getConnection(JDBC_URL_PREFIX + target.toAbsolutePath());
    }

    private static void deleteQuietly(Path pending) {
        try {
            Files.deleteIfExists(pending);
        } catch (IOException ioException) {
            log.debug("failed to remove {} after a failed write", pending, ioException);
        }
    }

    /** The tables the write fills, empty: dropping first makes the file the new snapshot, not a merge. */
    private static void createSchema(Connection connection) throws SQLException {
        try (Statement schema = connection.createStatement()) {
            schema.execute(DROP_METADATA);
            schema.execute(DROP_TILES);
            schema.execute(CREATE_METADATA);
            schema.execute(INDEX_METADATA_NAME);
            schema.execute(CREATE_TILES);
            schema.execute(INDEX_TILE_ADDRESS);
        }
    }

    private static void writeMetadata(Connection connection, String fingerprint) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(INSERT_METADATA)) {
            addMetadata(insert, NAME_METADATA_KEY, NAME_METADATA_VALUE);
            addMetadata(insert, FORMAT_METADATA_KEY, FORMAT_METADATA_VALUE);
            addMetadata(insert, FINGERPRINT_KEY, fingerprint);
            insert.executeBatch();
        }
    }

    private static void addMetadata(PreparedStatement insert, String name, String value) throws SQLException {
        insert.setString(1, name);
        insert.setString(2, value);
        insert.addBatch();
    }

    private static void writeTiles(Connection connection, Map<TileAddress, TilePayload> tiles) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(INSERT_TILE)) {
            for (Map.Entry<TileAddress, TilePayload> tile : tiles.entrySet()) {
                TileAddress address = tile.getKey();
                insert.setInt(1, address.zoom());
                insert.setInt(2, address.x());
                insert.setInt(3, tmsRow(address));
                insert.setBytes(4, tile.getValue().gzipped());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static Optional<String> readMetadata(Connection connection, String name) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(SELECT_METADATA)) {
            select.setString(1, name);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.ofNullable(row.getString("value")) : Optional.empty();
            }
        }
    }

    private static Map<TileAddress, TilePayload> readTiles(Connection connection) throws SQLException {
        Map<TileAddress, TilePayload> tiles = new HashMap<>();
        try (Statement select = connection.createStatement();
             ResultSet rows = select.executeQuery(SELECT_TILES)) {
            while (rows.next()) {
                int zoom = rows.getInt("zoom_level");
                int x = rows.getInt("tile_column");
                int y = xyzRow(zoom, rows.getInt("tile_row"));
                tiles.put(new TileAddress(zoom, x, y), TilePayload.ofGzipped(rows.getBytes("tile_data")));
            }
        }
        return tiles;
    }

    /** The TMS row MBTiles stores for an XYZ address: rows count up from the south edge. */
    private static int tmsRow(TileAddress address) {
        return SlippyMap.tilesPerAxis(address.zoom()) - 1 - address.y();
    }

    /** The XYZ row behind a stored TMS row; the flip is its own inverse. */
    private static int xyzRow(int zoom, int tmsRow) {
        return SlippyMap.tilesPerAxis(zoom) - 1 - tmsRow;
    }
}
