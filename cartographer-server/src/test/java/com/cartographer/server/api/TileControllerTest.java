package com.cartographer.server.api;

import com.cartographer.map.GraphEdge;
import com.cartographer.map.GraphNode;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;
import com.cartographer.model.Route;
import com.cartographer.server.config.ServerConfig;
import com.cartographer.server.graph.GraphService;
import com.cartographer.server.tiles.SlippyMap;
import com.cartographer.server.tiles.TileBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wdtinc.mapbox_vector_tile.VectorTile;
import com.wdtinc.mapbox_vector_tile.adapt.jts.MvtReader;
import com.wdtinc.mapbox_vector_tile.adapt.jts.TagKeyValueMapConverter;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsLayer;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsMvt;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level tests for the tile endpoints, over a stubbed {@link GraphService}
 * holding one edge: from the centre of tile 14/{@value #TILE_X}/{@value #TILE_Y}
 * to the centre of the tile east of it. The fixture sits on the tile's centre
 * line, so the edge is tile-local x 2048 → 4096 with y = 2048 — known numbers
 * that make the clipped result checkable by hand.
 *
 * <p>The clipping buffer is set to {@value #CUSTOM_BUFFER_UNITS} here rather
 * than the configured default of 64, so the decoded geometry shows whether the
 * property reached the builder: the edge ends at x = 4096 + 512 instead of
 * x = 4096 + 64.
 */
@WebMvcTest(TileController.class)
@Import(ServerConfig.class)
class TileControllerTest {

    private static final int ZOOM = 14;
    private static final int TILE_X = SlippyMap.tileX(13.0, ZOOM);
    private static final int TILE_Y = SlippyMap.tileY(48.0, ZOOM);
    private static final double CENTER_LATITUDE = SlippyMap.latitudeForTileY(TILE_Y + 0.5, ZOOM);
    private static final double CENTER_LONGITUDE = SlippyMap.longitudeForTileX(TILE_X + 0.5, ZOOM);
    private static final double TILE_WIDTH_DEGREES = 360.0 / (1 << ZOOM);
    private static final int CUSTOM_BUFFER_UNITS = 512;
    private static final MediaType MAPBOX_VECTOR_TILE = MediaType.parseMediaType("application/vnd.mapbox-vector-tile");
    private static final double WORLD_LATITUDE_LIMIT = 85.05112877980659;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GraphService graphService;

    @DynamicPropertySource
    static void tileBuffer(DynamicPropertyRegistry registry) {
        registry.add("cartographer.tile-buffer-units", () -> CUSTOM_BUFFER_UNITS);
    }

    @Test
    void tileEndpointServesTheGraphsEdgeAsDecodableMvt() throws Exception {
        GraphEdge edge = useFixtureGraph();

        byte[] body = mockMvc.perform(get("/tiles/{z}/{x}/{y}.mvt", ZOOM, TILE_X, TILE_Y))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MAPBOX_VECTOR_TILE))
                .andReturn().getResponse().getContentAsByteArray();

        LineString line = onlyLine(body);
        // the buffered stub of the edge: 4096 + CUSTOM_BUFFER_UNITS, not 4096 + 64
        assertCoordinates(line.getCoordinates(), 2048, 2048, 4096 + CUSTOM_BUFFER_UNITS, 2048);
        assertEquals(Map.of(
                "traversals", 1L,
                "lengthMeters", edge.getLengthMeters(),
                "shared", false,
                "routeCount", 1L), line.getUserData());
    }

    @Test
    void tileWithoutTheGraphIsAnEmptyTile() throws Exception {
        useFixtureGraph();
        // a quarter of the world east of the fixture tile: inside the grid, no edge
        int emptyTile = SlippyMap.tileX(CENTER_LONGITUDE + 90.0, ZOOM);

        byte[] body = mockMvc.perform(get("/tiles/{z}/{x}/{y}.mvt", ZOOM, emptyTile, TILE_Y))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MAPBOX_VECTOR_TILE))
                .andReturn().getResponse().getContentAsByteArray();

        assertEquals(0, body.length);
        assertEquals(0, VectorTile.Tile.parseFrom(body).getLayersCount());
    }

    @Test
    void addressesOutsideTheTileGridAreRejected() throws Exception {
        mockMvc.perform(get("/tiles/23/0/0.mvt")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/tiles/-1/0/0.mvt")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/tiles/14/-1/5766.mvt")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/tiles/14/16384/5766.mvt")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/tiles/14/8692/16384.mvt")).andExpect(status().isBadRequest());
    }

    @Test
    void tileJsonDescribesTheEdgesLayerAndTheGraphExtent() throws Exception {
        useFixtureGraph();

        String body = mockMvc.perform(get("/tiles/tilejson.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        JsonNode tileJson = objectMapper.readTree(body);

        assertEquals("2.2.0", tileJson.path("tilejson").asText());
        assertEquals("cartographer route graph", tileJson.path("name").asText());
        assertEquals("/tiles/{z}/{x}/{y}.mvt", tileJson.path("tiles").get(0).asText());
        assertEquals(0, tileJson.path("minzoom").asInt());
        assertEquals(SlippyMap.MAX_ZOOM, tileJson.path("maxzoom").asInt());
        // [west, south, east, north] of the fixture edge
        assertEquals(CENTER_LONGITUDE, tileJson.path("bounds").get(0).asDouble(), 1e-9);
        assertEquals(CENTER_LATITUDE, tileJson.path("bounds").get(1).asDouble(), 1e-9);
        assertEquals(CENTER_LONGITUDE + TILE_WIDTH_DEGREES, tileJson.path("bounds").get(2).asDouble(), 1e-9);
        assertEquals(CENTER_LATITUDE, tileJson.path("bounds").get(3).asDouble(), 1e-9);

        JsonNode layer = tileJson.path("vector_layers").get(0);
        assertEquals(TileBuilder.LAYER_NAME, layer.path("id").asText());
        assertEquals("Number", layer.path("fields").path("traversals").asText());
        assertEquals("Number", layer.path("fields").path("lengthMeters").asText());
        assertEquals("Boolean", layer.path("fields").path("shared").asText());
        assertEquals("Number", layer.path("fields").path("routeCount").asText());
    }

    @Test
    void tileJsonOfAnEmptyGraphCoversTheWorldSquare() throws Exception {
        when(graphService.graph()).thenReturn(new RouteGraph());

        String body = mockMvc.perform(get("/tiles/tilejson.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode bounds = objectMapper.readTree(body).path("bounds");

        assertEquals(-180.0, bounds.get(0).asDouble(), 1e-9);
        assertEquals(-WORLD_LATITUDE_LIMIT, bounds.get(1).asDouble(), 1e-9);
        assertEquals(180.0, bounds.get(2).asDouble(), 1e-9);
        assertEquals(WORLD_LATITUDE_LIMIT, bounds.get(3).asDouble(), 1e-9);
    }

    /** The fixture edge, from the tile centre to the centre of the next tile; returns it for its properties. */
    private GraphEdge useFixtureGraph() {
        Node start = new Node(CENTER_LATITUDE, CENTER_LONGITUDE);
        Node end = new Node(CENTER_LATITUDE, CENTER_LONGITUDE + TILE_WIDTH_DEGREES);
        Route route = new Route("route-a");
        route.addNode(start);
        route.addNode(end);

        RouteGraph graph = new RouteGraph();
        GraphNode from = graph.addEndpoint(start);
        GraphNode to = graph.addEndpoint(end);
        graph.addEdge(new GraphEdge(from, to, List.of(start, end), route));

        when(graphService.graph()).thenReturn(graph);
        return graph.getEdges().get(0);
    }

    private static LineString onlyLine(byte[] tile) throws IOException {
        JtsMvt decoded = MvtReader.loadMvt(new ByteArrayInputStream(tile),
                new GeometryFactory(), new TagKeyValueMapConverter(true));
        JtsLayer layer = decoded.getLayer(TileBuilder.LAYER_NAME);
        assertNotNull(layer, "tile has no " + TileBuilder.LAYER_NAME + " layer");
        List<LineString> lines = new ArrayList<>();
        for (Geometry geometry : layer.getGeometries()) {
            lines.add((LineString) geometry);
        }
        assertEquals(1, lines.size(), "expected exactly one feature");
        return lines.get(0);
    }

    /** Asserts the decoded coordinates, given as x, y pairs. */
    private static void assertCoordinates(Coordinate[] actual, int... expected) {
        assertEquals(expected.length / 2, actual.length, "point count");
        for (int i = 0; i < expected.length; i += 2) {
            int point = i / 2;
            assertEquals(expected[i], (int) actual[point].x, "x of point " + point);
            assertEquals(expected[i + 1], (int) actual[point].y, "y of point " + point);
        }
    }
}
