package com.cartographer.server.tiles;

import com.cartographer.map.RouteGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The tiles the current graph renders to, encoded once at rebuild time and
 * served from memory.
 *
 * <p>{@link #preRender} turns a graph into an address → payload snapshot and
 * swaps it in whole: a request sees the previous graph's tiles or the new
 * graph's, never a half-filled map, and no request encodes a tile. A rebuild
 * replaces the snapshot, so there is no eviction and no per-address
 * invalidation.
 *
 * <p>The snapshot is written to an {@link MBTilesStore} after the pre-render, and
 * {@link #restore} loads it back on a later start: when the store was rendered
 * from the same routes and settings, the pre-render — by far the most expensive
 * part of a rebuild — is skipped entirely.
 *
 * <p>The snapshot holds every address of the {@link TilePyramid} over the
 * configured zoom range. An address inside the slippy grid but outside the
 * pyramid is answered with the empty tile — the zero-layer vector tile the
 * builder produces where no edge is visible — so a map client can request the
 * whole grid without special cases.
 */
public final class TileCache {

    private static final Logger log = LoggerFactory.getLogger(TileCache.class);

    /** The payload of a tile no edge reaches; gzipped and hashed once, like any other payload. */
    private static final TilePayload EMPTY_TILE = TilePayload.of(new byte[0]);

    private final TileBuilder tileBuilder;
    private final MBTilesStore store;
    private final int minZoom;
    private final int maxZoom;

    private volatile Map<TileAddress, TilePayload> tiles = Map.of();

    /**
     * @param minZoom lowest zoom level pre-rendered on a rebuild
     * @param maxZoom highest zoom level pre-rendered on a rebuild
     * @param store where the pre-rendered snapshot is kept between restarts
     */
    public TileCache(TileBuilder tileBuilder, int minZoom, int maxZoom, MBTilesStore store) {
        if (minZoom < 0 || maxZoom > SlippyMap.MAX_ZOOM || minZoom > maxZoom) {
            throw new IllegalArgumentException("zoom range must satisfy 0 <= min <= max <= "
                    + SlippyMap.MAX_ZOOM + ": " + minZoom + ".." + maxZoom);
        }
        this.tileBuilder = tileBuilder;
        this.store = store;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
    }

    /**
     * Encodes every tile of the graph's {@link TilePyramid} for the configured
     * zoom range, logs what each zoom level costs, and swaps the result in as
     * the snapshot new requests are served from. Synchronous: when this returns,
     * readers see only the new graph's tiles.
     */
    public void preRender(RouteGraph graph) {
        long startedAtNanos = System.nanoTime();
        TilePyramid pyramid = TilePyramid.of(graph, tileBuilder.bufferUnits());
        Map<TileAddress, TilePayload> rendered = new HashMap<>();
        for (int zoom = minZoom; zoom <= maxZoom; zoom++) {
            long zoomStartedAtNanos = System.nanoTime();
            TilePyramid.TileRange range = pyramid.rangeAt(zoom);
            for (int x = range.minX(); x <= range.maxX(); x++) {
                for (int y = range.minY(); y <= range.maxY(); y++) {
                    byte[] tile = tileBuilder.buildTile(graph, zoom, x, y);
                    rendered.put(new TileAddress(zoom, x, y), tile.length == 0 ? EMPTY_TILE : TilePayload.of(tile));
                }
            }
            log.info("Pre-rendered {} tiles at zoom {} in {} ms", range.tileCount(), zoom,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - zoomStartedAtNanos));
        }
        this.tiles = Map.copyOf(rendered);
        log.info("Pre-rendered {} tiles for zooms {}..{} in {} ms", rendered.size(), minZoom, maxZoom,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
    }

    /** The pre-rendered payload for an address, or the empty tile when the graph does not reach it. */
    public TilePayload payload(int zoom, int x, int y) {
        return tiles.getOrDefault(new TileAddress(zoom, x, y), EMPTY_TILE);
    }

    /** The current snapshot of pre-rendered tiles, by address; it is immutable and never mutated. */
    public Map<TileAddress, TilePayload> tiles() {
        return tiles;
    }

    /**
     * Replaces the snapshot with the one the store holds for {@code fingerprint},
     * if it holds one: the tiles of an unchanged routes directory and tile setup,
     * byte for byte as they were rendered last time.
     *
     * <p>This is what makes a restart cheap: the returned {@code true} means
     * {@link #preRender} can be skipped. The snapshot is swapped in whole, so a
     * reader sees either the previous tiles or the restored ones. When the store
     * is absent or was rendered from other inputs, the snapshot is left as it was
     * and {@code false} asks the caller to render.
     */
    public boolean restore(String fingerprint) {
        long startedAtNanos = System.nanoTime();
        Optional<Map<TileAddress, TilePayload>> restored = store.read(fingerprint);
        if (restored.isEmpty()) {
            return false;
        }
        this.tiles = Map.copyOf(restored.get());
        log.info("Restored {} tiles for zooms {}..{} from {} in {} ms; skipping the pre-render",
                tiles.size(), minZoom, maxZoom, store.file().toAbsolutePath(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
        return true;
    }

    /**
     * Writes the current snapshot to the store, stamped with the fingerprint of
     * the inputs it was rendered from, so the next start can restore it instead
     * of rendering again. Best effort: the store logs a write that fails and the
     * server keeps serving from memory.
     */
    public void persist(String fingerprint) {
        store.write(fingerprint, tiles);
    }

    /** One tile address: zoom level, column and row. */
    public record TileAddress(int zoom, int x, int y) {
    }
}
