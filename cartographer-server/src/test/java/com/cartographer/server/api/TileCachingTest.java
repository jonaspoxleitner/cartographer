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
import com.cartographer.server.tiles.TileCache;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level tests for the caching behavior of the tile endpoint: the
 * {@code Cache-Control} from configuration, the {@code Vary: Accept-Encoding}
 * that keeps the two body forms apart, gzip for clients that accept it, the raw
 * bytes for clients that do not, and {@code 304} for a request repeating the
 * ETag.
 *
 * <p>The fixture is the one {@link TileControllerTest} uses: a stubbed
 * {@link GraphService} with one edge from the centre of tile
 * 14/{@value #TILE_X}/{@value #TILE_Y} to the centre of the tile east of it, so
 * the decoded geometry is tile-local x 2048 → 4096 plus the default clipping
 * buffer at y 2048. The fixture graph is pre-rendered into the {@link TileCache}
 * at the one zoom level this test pre-renders, as a rebuild does.
 */
@WebMvcTest(TileController.class)
@Import(ServerConfig.class)
class TileCachingTest {

    private static final int ZOOM = 14;
    private static final int TILE_X = SlippyMap.tileX(13.0, ZOOM);
    private static final int TILE_Y = SlippyMap.tileY(48.0, ZOOM);
    private static final double CENTER_LATITUDE = SlippyMap.latitudeForTileY(TILE_Y + 0.5, ZOOM);
    private static final double CENTER_LONGITUDE = SlippyMap.longitudeForTileX(TILE_X + 0.5, ZOOM);
    private static final double TILE_WIDTH_DEGREES = 360.0 / (1 << ZOOM);
    private static final int MAX_AGE_SECONDS = 120;
    private static final MediaType MAPBOX_VECTOR_TILE = MediaType.parseMediaType("application/vnd.mapbox-vector-tile");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GraphService graphService;

    @Autowired
    private TileCache tileCache;

    @DynamicPropertySource
    static void tileCacheMaxAge(DynamicPropertyRegistry registry) {
        registry.add("cartographer.tile-cache-max-age-seconds", () -> MAX_AGE_SECONDS);
        registry.add("cartographer.tile-cache-min-zoom", () -> ZOOM);
        registry.add("cartographer.tile-cache-max-zoom", () -> ZOOM);
    }

    @Test
    void gzipClientGetsTheGzippedTile() throws Exception {
        useFixtureGraph();

        MockHttpServletResponse response = mockMvc.perform(get("/tiles/{z}/{x}/{y}.mvt", ZOOM, TILE_X, TILE_Y)
                        .header(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate, br"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MAPBOX_VECTOR_TILE))
                .andExpect(header().string(HttpHeaders.CONTENT_ENCODING, "gzip"))
                .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=" + MAX_AGE_SECONDS))
                .andReturn().getResponse();

        assertQuotedEtag(response.getHeader(HttpHeaders.ETAG));
        LineString line = onlyLine(gunzip(response.getContentAsByteArray()));
        // the edge, clipped at the tile box plus the default buffer
        assertCoordinates(line.getCoordinates(), 2048, 2048,
                SlippyMap.TILE_EXTENT + TileBuilder.DEFAULT_BUFFER_UNITS, 2048);
    }

    @Test
    void clientWithoutGzipGetsTheRawBytesOfTheSameTile() throws Exception {
        useFixtureGraph();

        MockHttpServletResponse gzipped = mockMvc.perform(get("/tiles/{z}/{x}/{y}.mvt", ZOOM, TILE_X, TILE_Y)
                        .header(HttpHeaders.ACCEPT_ENCODING, "gzip"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        // no Accept-Encoding header at all: the common case for a plain client
        MockHttpServletResponse raw = mockMvc.perform(get("/tiles/{z}/{x}/{y}.mvt", ZOOM, TILE_X, TILE_Y))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MAPBOX_VECTOR_TILE))
                .andReturn().getResponse();

        assertNull(raw.getHeader(HttpHeaders.CONTENT_ENCODING), "raw tile must not claim a content coding");
        assertArrayEquals(gunzip(gzipped.getContentAsByteArray()), raw.getContentAsByteArray());
        // one ETag over the tile bytes, so both forms revalidate against the same tag
        assertEquals(gzipped.getHeader(HttpHeaders.ETAG), raw.getHeader(HttpHeaders.ETAG));
        onlyLine(raw.getContentAsByteArray());
    }

    @Test
    void gzipRefusedByItsQualityValueGetsTheRawTile() throws Exception {
        useFixtureGraph();

        MockHttpServletResponse response = mockMvc.perform(get("/tiles/{z}/{x}/{y}.mvt", ZOOM, TILE_X, TILE_Y)
                        .header(HttpHeaders.ACCEPT_ENCODING, "gzip;q=0, identity"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertNull(response.getHeader(HttpHeaders.CONTENT_ENCODING), "gzip;q=0 must not get a gzipped tile");
        onlyLine(response.getContentAsByteArray());
    }

    @Test
    void repeatRequestWithTheEtagIsNotModified() throws Exception {
        useFixtureGraph();
        String etag = mockMvc.perform(get("/tiles/{z}/{x}/{y}.mvt", ZOOM, TILE_X, TILE_Y))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        assertQuotedEtag(etag);

        MockHttpServletResponse response = mockMvc.perform(get("/tiles/{z}/{x}/{y}.mvt", ZOOM, TILE_X, TILE_Y)
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andReturn().getResponse();

        assertEquals(0, response.getContentAsByteArray().length, "304 must not carry a body");

        MockHttpServletResponse gzipResponse = mockMvc.perform(get("/tiles/{z}/{x}/{y}.mvt", ZOOM, TILE_X, TILE_Y)
                        .header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andReturn().getResponse();

        assertEquals(0, gzipResponse.getContentAsByteArray().length, "304 must not carry a body for gzip clients");
    }

    /** The fixture edge, from the tile centre to the centre of the next tile. */
    private void useFixtureGraph() {
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
        tileCache.preRender(graph);
    }

    /** RFC 9110 strong ETags are quoted; an unquoted value would be compared as a weak tag. */
    private static void assertQuotedEtag(String etag) {
        assertNotNull(etag, "no ETag on the tile response");
        assertTrue(etag.startsWith("\"") && etag.endsWith("\""), "ETag is not quoted: " + etag);
    }

    private static byte[] gunzip(byte[] gzipped) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return in.readAllBytes();
        } catch (IOException ioException) {
            throw new UncheckedIOException("response body is not gzip", ioException);
        }
    }

    /** Decodes the tile and asserts it holds exactly one feature in the edges layer. */
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
