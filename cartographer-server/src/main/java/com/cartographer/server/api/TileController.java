package com.cartographer.server.api;

import com.cartographer.map.GraphEdge;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;
import com.cartographer.server.graph.GraphService;
import com.cartographer.server.tiles.SlippyMap;
import com.cartographer.server.tiles.TileBuilder;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the current graph as vector tiles and publishes the TileJSON document
 * that describes them.
 *
 * <p>Addresses outside the slippy-map grid are {@code 400}; an address inside
 * the grid without a visible edge is {@code 200} with the empty tile (a
 * vector tile with zero layers), so a map client can request the whole
 * pyramid without special cases.
 */
@RestController
@RequestMapping("/tiles")
public class TileController {

    /** TileJSON version this document follows. */
    private static final String TILEJSON_VERSION = "2.2.0";

    /** URL template of the tile endpoint, as TileJSON lists it. */
    private static final String TILE_URL_TEMPLATE = "/tiles/{z}/{x}/{y}.mvt";

    /** Media type of the tile endpoint; the value the mapping declares as its produced type. */
    private static final String MAPBOX_VECTOR_TILE_VALUE = "application/vnd.mapbox-vector-tile";

    private static final MediaType MAPBOX_VECTOR_TILE = MediaType.parseMediaType(MAPBOX_VECTOR_TILE_VALUE);

    private final GraphService graphService;
    private final TileBuilder tileBuilder;

    public TileController(GraphService graphService, TileBuilder tileBuilder) {
        this.graphService = graphService;
        this.tileBuilder = tileBuilder;
    }

    @GetMapping(value = "/{z}/{x}/{y}.mvt", produces = MAPBOX_VECTOR_TILE_VALUE)
    public ResponseEntity<byte[]> tile(@PathVariable("z") int z, @PathVariable("x") int x,
                                       @PathVariable("y") int y) {
        requireAddressInGrid(z, x, y);
        byte[] tile = tileBuilder.buildTile(graphService.graph(), z, x, y);
        return ResponseEntity.ok().contentType(MAPBOX_VECTOR_TILE).body(tile);
    }

    @GetMapping(value = "/tilejson.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public TileJson tileJson() {
        return new TileJson(TILEJSON_VERSION, "cartographer route graph", List.of(TILE_URL_TEMPLATE),
                0, SlippyMap.MAX_ZOOM, bounds(graphService.graph()),
                List.of(new VectorLayer(TileBuilder.LAYER_NAME, "Route graph edges", 0, SlippyMap.MAX_ZOOM,
                        edgeFields())));
    }

    /** Rejects zoom levels and column/row values outside the grid before any tile is built. */
    private static void requireAddressInGrid(int z, int x, int y) {
        if (z < 0 || z > SlippyMap.MAX_ZOOM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "zoom out of range 0.." + SlippyMap.MAX_ZOOM + ": " + z);
        }
        int tilesPerAxis = SlippyMap.tilesPerAxis(z);
        if (x < 0 || x >= tilesPerAxis || y < 0 || y >= tilesPerAxis) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tile " + x + "/" + y + " is outside 0.." + (tilesPerAxis - 1) + " at zoom " + z);
        }
    }

    /**
     * {@code [west, south, east, north]} over every path point of the graph, or
     * the world square for an empty graph: TileJSON requires four values, and
     * the tile grid that world square comes from is the one clients request.
     */
    private static List<Double> bounds(RouteGraph graph) {
        double west = Double.POSITIVE_INFINITY;
        double south = Double.POSITIVE_INFINITY;
        double east = Double.NEGATIVE_INFINITY;
        double north = Double.NEGATIVE_INFINITY;
        for (GraphEdge edge : graph.getEdges()) {
            for (Node node : edge.getPath()) {
                west = Math.min(west, node.getLongitude());
                east = Math.max(east, node.getLongitude());
                south = Math.min(south, node.getLatitude());
                north = Math.max(north, node.getLatitude());
            }
        }
        if (west > east) {
            return worldBounds();
        }
        return List.of(west, south, east, north);
    }

    /** The Web Mercator world square, expressed through the zoom 0 tile's corners. */
    private static List<Double> worldBounds() {
        return List.of(SlippyMap.longitudeForTileX(0, 0), SlippyMap.latitudeForTileY(1, 0),
                SlippyMap.longitudeForTileX(1, 0), SlippyMap.latitudeForTileY(0, 0));
    }

    /** The four properties {@link TileBuilder} writes on every edge feature. */
    private static Map<String, String> edgeFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("traversals", "Number");
        fields.put("lengthMeters", "Number");
        fields.put("shared", "Boolean");
        fields.put("routeCount", "Number");
        return fields;
    }

    /**
     * TileJSON 2.2 metadata: where the tiles are, which zoom levels and area
     * they cover, and which layer and fields they contain.
     */
    public record TileJson(
            String tilejson,
            String name,
            List<String> tiles,
            int minzoom,
            int maxzoom,
            List<Double> bounds,
            @JsonProperty("vector_layers") List<VectorLayer> vectorLayers) {
    }

    /** One TileJSON {@code vector_layers} entry: the layer id and its property fields by type. */
    public record VectorLayer(String id, String description, int minzoom, int maxzoom, Map<String, String> fields) {
    }
}
