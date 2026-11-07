package com.cartographer.server.api;

import com.cartographer.io.GpxRouteLoader;
import com.cartographer.map.GraphBuilder;
import com.cartographer.map.IntersectionFinder;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;
import com.cartographer.model.Route;
import com.cartographer.server.config.ServerConfig;
import com.cartographer.server.config.ServerProperties;
import com.cartographer.server.graph.GraphService;
import com.cartographer.server.tiles.TileCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level tests for the GeoJSON endpoint and the GPX upload. The graph is
 * built for real from a seeded routes directory, so an upload is verified by
 * the graph the next GET returns. Fixtures: route-a runs west-east, route-b
 * crosses it at (48.0, 13.002) — four edges, no shared stretch.
 */
@WebMvcTest(GraphController.class)
@Import({GeoJsonMapper.class, ServerConfig.class, GraphControllerTest.RealGraphService.class})
class GraphControllerTest {

    private static final MediaType GEO_JSON = MediaType.parseMediaType("application/geo+json");
    private static final double TOLERANCE_METERS = 10.0;
    private static final List<String> FIXTURES = List.of("route-a.gpx", "route-b.gpx");
    private static final Path ROUTES_DIR = seedRoutesDir();
    /** Outside the routes directory: the rebuild writes it, and @BeforeEach clears that directory. */
    private static final Path TILES_FILE = ROUTES_DIR.resolveSibling("graph-controller-test-tiles.mbtiles");

    /** A route far from the fixtures: one more edge, no junctions. */
    private static final String NEW_ROUTE_GPX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="cartographer-test" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <name>Route C</name>
                <trkseg>
                  <trkpt lat="48.010000" lon="13.000000"/>
                  <trkpt lat="48.011000" lon="13.000000"/>
                </trkseg>
              </trk>
            </gpx>
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GraphService graphService;

    @DynamicPropertySource
    static void routesDirectory(DynamicPropertyRegistry registry) {
        registry.add("cartographer.routes-dir", () -> ROUTES_DIR.toString());
        registry.add("cartographer.intersection-tolerance-meters", () -> TOLERANCE_METERS);
        registry.add("cartographer.mbtiles-file", () -> TILES_FILE.toString());
    }

    @BeforeEach
    void rebuildFromFixtureRoutes() throws IOException {
        try (Stream<Path> files = Files.list(ROUTES_DIR)) {
            for (Path file : files.filter(f -> !FIXTURES.contains(f.getFileName().toString())).toList()) {
                Files.delete(file);
            }
        }
        graphService.rebuild();
    }

    @AfterAll
    static void deleteRoutesDir() throws IOException {
        try (Stream<Path> files = Files.list(ROUTES_DIR)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(ROUTES_DIR);
        Files.deleteIfExists(TILES_FILE);
    }

    @Test
    void graphEndpointServesTheBuiltGraphAsGeoJsonEdgesAndVertexPoints() throws Exception {
        JsonNode featureCollection = graphJson();

        assertEquals("FeatureCollection", featureCollection.path("type").asText());
        List<JsonNode> edges = featuresOfType(featureCollection, "LineString");
        assertEquals(4, edges.size(), "route-a cut at the crossing plus route-b's two halves");

        for (JsonNode feature : edges) {
            assertEquals("Feature", feature.path("type").asText());

            JsonNode properties = feature.path("properties");
            assertEquals(1, properties.path("traversals").asInt());
            assertFalse(properties.path("shared").asBoolean());
            assertTrue(properties.path("lengthMeters").asDouble() > 0);
            assertFalse(properties.path("routes").isEmpty());

            for (JsonNode position : feature.path("geometry").path("coordinates")) {
                // GeoJSON position order: longitude first, then latitude
                assertEquals(13.0, position.get(0).asDouble(), 0.01);
                assertEquals(48.0, position.get(1).asDouble(), 0.01);
            }
        }

        List<JsonNode> vertices = featuresOfType(featureCollection, "Point");
        assertEquals(5, vertices.size(), "four route ends plus the junction where the routes cross");
        Set<String> edgeEndpoints = edgeEndpoints(edges);
        for (JsonNode vertex : vertices) {
            assertEquals("Feature", vertex.path("type").asText());
            JsonNode position = vertex.path("geometry").path("coordinates");
            assertEquals(2, position.size(), "a Point carries one position");
            assertTrue(edgeEndpoints.contains(positionKey(position)),
                    "a vertex Point sits exactly on an edge endpoint: " + position);
        }

        List<JsonNode> junctionVertices = vertices.stream()
                .filter(vertex -> vertex.path("properties").path("junction").asBoolean())
                .toList();
        assertEquals(1, junctionVertices.size(), "one junction, four route ends");
        JsonNode junctionPosition = junctionVertices.get(0).path("geometry").path("coordinates");
        assertEquals(13.002, junctionPosition.get(0).asDouble(), 1e-6);
        assertEquals(48.0, junctionPosition.get(1).asDouble(), 1e-6);
    }

    @Test
    void geoJsonMarksEdgesTraveledMoreThanOnceAsShared() {
        GeoJsonMapper.FeatureCollection featureCollection = new GeoJsonMapper().toFeatureCollection(overlappingGraph());

        List<GeoJsonMapper.EdgeProperties> shared = edgeProperties(featureCollection).stream()
                .filter(GeoJsonMapper.EdgeProperties::shared)
                .toList();
        assertFalse(shared.isEmpty(), "two routes on one street share edges");
        for (GeoJsonMapper.EdgeProperties properties : shared) {
            assertTrue(properties.traversals() > 1);
            assertEquals(List.of("Route X", "Route Y"), properties.routes());
        }
    }

    @Test
    void uploadStoresGpxAndSwapsInTheRebuiltGraph() throws Exception {
        mockMvc.perform(uploadGpx("route-c.gpx", NEW_ROUTE_GPX))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("route-c.gpx"))
                .andExpect(jsonPath("$.edges").value(5));

        assertTrue(Files.isRegularFile(ROUTES_DIR.resolve("route-c.gpx")));
        assertEdgeCount(5);
    }

    @Test
    void uploadRejectsFilesThatAreNotGpx() throws Exception {
        mockMvc.perform(uploadGpx("notes.txt", NEW_ROUTE_GPX))
                .andExpect(status().isBadRequest());

        assertFalse(Files.exists(ROUTES_DIR.resolve("notes.txt")));
        assertEdgeCount(4);
    }

    @Test
    void uploadRejectsUnparseableGpx() throws Exception {
        mockMvc.perform(uploadGpx("broken.gpx", "<gpx><trk><trkseg>"))
                .andExpect(status().isBadRequest());

        assertFalse(Files.exists(ROUTES_DIR.resolve("broken.gpx")));
        assertEdgeCount(4);
    }

    @Test
    void uploadRejectsDuplicateFileNameAndKeepsTheExistingRoute() throws Exception {
        Path existing = ROUTES_DIR.resolve("route-a.gpx");
        byte[] original = Files.readAllBytes(existing);

        mockMvc.perform(uploadGpx("route-a.gpx", NEW_ROUTE_GPX))
                .andExpect(status().isConflict());

        assertArrayEquals(original, Files.readAllBytes(existing));
        assertEdgeCount(4);
    }

    @Test
    void uploadSavesOnlyTheBaseNameOfTheSubmittedPath() throws Exception {
        mockMvc.perform(uploadGpx("../../route-d.gpx", NEW_ROUTE_GPX))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("route-d.gpx"));

        assertTrue(Files.isRegularFile(ROUTES_DIR.resolve("route-d.gpx")));
        assertFalse(Files.exists(ROUTES_DIR.getParent().resolve("route-d.gpx")));
    }

    private MockHttpServletRequestBuilder uploadGpx(String filename, String content) {
        return multipart("/api/routes").file(new MockMultipartFile("file", filename, "application/gpx+xml",
                content.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertEdgeCount(int expected) throws Exception {
        assertEquals(expected, featuresOfType(graphJson(), "LineString").size());
    }

    private JsonNode graphJson() throws Exception {
        String body = mockMvc.perform(get("/api/graph"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(GEO_JSON))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** The features whose geometry is of {@code geometryType}, in document order. */
    private static List<JsonNode> featuresOfType(JsonNode featureCollection, String geometryType) {
        List<JsonNode> features = new ArrayList<>();
        for (JsonNode feature : featureCollection.path("features")) {
            if (geometryType.equals(feature.path("geometry").path("type").asText())) {
                features.add(feature);
            }
        }
        return features;
    }

    /** Every edge path end as a comparable coordinate key. */
    private static Set<String> edgeEndpoints(List<JsonNode> edges) {
        Set<String> endpoints = new HashSet<>();
        for (JsonNode edge : edges) {
            JsonNode coordinates = edge.path("geometry").path("coordinates");
            endpoints.add(positionKey(coordinates.get(0)));
            endpoints.add(positionKey(coordinates.get(coordinates.size() - 1)));
        }
        return endpoints;
    }

    private static String positionKey(JsonNode position) {
        return position.get(0).asDouble() + "," + position.get(1).asDouble();
    }

    private static List<GeoJsonMapper.EdgeProperties> edgeProperties(
            GeoJsonMapper.FeatureCollection featureCollection) {
        return featureCollection.features().stream()
                .map(GeoJsonMapper.Feature::properties)
                .filter(GeoJsonMapper.EdgeProperties.class::isInstance)
                .map(GeoJsonMapper.EdgeProperties.class::cast)
                .toList();
    }

    /** Two routes overlapping on one street — the same shape core's GraphBuilderTest covers. */
    private static RouteGraph overlappingGraph() {
        Route x = route("Route X",
                new Node(48.0000, 13.0), new Node(48.0010, 13.0), new Node(48.0020, 13.0),
                new Node(48.0030, 13.0), new Node(48.0040, 13.0));
        Route y = route("Route Y",
                new Node(48.0050, 13.0), new Node(48.0030, 13.0), new Node(48.0020, 13.0),
                new Node(48.0010, 13.0), new Node(48.0005, 13.0));
        List<Route> routes = List.of(x, y);
        GraphBuilder graphBuilder = new GraphBuilder(TOLERANCE_METERS);
        return graphBuilder.simplify(
                graphBuilder.build(routes, new IntersectionFinder(TOLERANCE_METERS).find(routes)));
    }

    private static Route route(String name, Node... nodes) {
        Route route = new Route(name);
        for (Node node : nodes) {
            route.addNode(node);
        }
        return route;
    }

    private static Path seedRoutesDir() {
        try {
            Path routesDir = Files.createTempDirectory("cartographer-server-test-routes");
            for (String fixture : FIXTURES) {
                Files.copy(resourcePath("/gpx/" + fixture), routesDir.resolve(fixture));
            }
            return routesDir;
        } catch (IOException ioException) {
            throw new UncheckedIOException("failed to seed the test routes directory", ioException);
        }
    }

    private static Path resourcePath(String name) {
        try {
            return Path.of(Objects.requireNonNull(GraphControllerTest.class.getResource(name),
                    "missing test resource " + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("not a file path: " + name, e);
        }
    }

    /** The real service over the seeded directory; @PostConstruct builds the graph on context startup. */
    @TestConfiguration
    static class RealGraphService {

        @Bean
        GraphService graphService(ServerProperties properties, GpxRouteLoader routeLoader,
                                  IntersectionFinder intersectionFinder, GraphBuilder graphBuilder,
                                  TileCache tileCache) {
            return new GraphService(properties, routeLoader, intersectionFinder, graphBuilder, tileCache);
        }
    }
}
