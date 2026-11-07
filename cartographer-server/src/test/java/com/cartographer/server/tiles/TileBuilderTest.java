package com.cartographer.server.tiles;

import com.cartographer.map.GraphBuilder;
import com.cartographer.map.GraphEdge;
import com.cartographer.map.GraphNode;
import com.cartographer.map.IntersectionFinder;
import com.cartographer.map.RouteGraph;
import com.cartographer.model.Node;
import com.cartographer.model.Route;
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
import org.locationtech.jts.geom.Point;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips tiles built from a tiny synthetic graph through the test-scope
 * {@code mapbox-vector-tile} decoder: clipping at a tile boundary, the seam
 * between neighbouring tiles, the buffer, the coordinate-collapse rule, the
 * edge properties and the junction dots of the second layer.
 *
 * <p>The fixture sits in tile 14/8692/5766 (46.94°N 11.00°E) and is chosen so
 * the numbers come out round: the tile's centre line is tile-local x = 2048 and
 * the row's centre line is tile-local y = 2048, so an edge from the tile centre
 * to the centre of the next tile runs 2048 → 4096 in this tile and 0 → 2048 in
 * the next one.
 */
class TileBuilderTest {

    private static final int ZOOM = 14;
    private static final int TILE_X = 8692;
    private static final int TILE_Y = 5766;

    /** Latitude of the fixture row's centre line: tile-local y 2048. */
    private static final double CENTER_LATITUDE = SlippyMap.latitudeForTileY(TILE_Y + 0.5, ZOOM);
    /** Longitude of the fixture tile's centre line: tile-local x 2048 in {@link #TILE_X}. */
    private static final double CENTER_LONGITUDE = SlippyMap.longitudeForTileX(TILE_X + 0.5, ZOOM);
    /** Longitude of the edge shared by {@link #TILE_X} and {@link #TILE_X} + 1. */
    private static final double BOUNDARY_LONGITUDE = SlippyMap.longitudeForTileX(TILE_X + 1, ZOOM);
    /** Longitudinal width of one tile at {@link #ZOOM}. */
    private static final double TILE_WIDTH_DEGREES = 360.0 / (1 << ZOOM);

    private final TileBuilder builder = new TileBuilder();
    private final TileBuilder unbuffered = new TileBuilder(0);

    @Test
    void edgeCrossingTileBoundaryIsClippedAtTheBoundaryInBothTiles() throws IOException {
        // centre of tile 8692 -> centre of tile 8693, both on the row's centre line
        RouteGraph graph = graphWith(
                node(CENTER_LATITUDE, CENTER_LONGITUDE),
                node(CENTER_LATITUDE, CENTER_LONGITUDE + TILE_WIDTH_DEGREES));

        // this tile keeps the western half, up to the boundary at x = 4096
        assertCoordinates(onlyLine(unbuffered.buildTile(graph, ZOOM, TILE_X, TILE_Y)), 2048, 2048, 4096, 2048);
        // the next tile keeps the eastern half, starting at its own x = 0
        assertCoordinates(onlyLine(unbuffered.buildTile(graph, ZOOM, TILE_X + 1, TILE_Y)), 0, 2048, 2048, 2048);
    }

    @Test
    void bufferExtendsGeometryPastTheTileEdge() throws IOException {
        RouteGraph graph = graphWith(
                node(CENTER_LATITUDE, CENTER_LONGITUDE),
                node(CENTER_LATITUDE, CENTER_LONGITUDE + TILE_WIDTH_DEGREES));

        // the default 64-unit buffer keeps drawing past the tile edge, so a stroke
        // along the boundary is not cut; this tile reaches x = 4160, the next one x = -64
        assertCoordinates(onlyLine(builder.buildTile(graph, ZOOM, TILE_X, TILE_Y)), 2048, 2048, 4160, 2048);
        assertCoordinates(onlyLine(builder.buildTile(graph, ZOOM, TILE_X + 1, TILE_Y)), -64, 2048, 2048, 2048);
    }

    @Test
    void adjacentTilesAgreeOnTheSharedBoundaryCoordinates() throws IOException {
        // diagonal across the boundary: (2048, 2594) -> (6144, 1502) in this tile's coordinates
        RouteGraph graph = graphWith(
                node(CENTER_LATITUDE - 0.002, CENTER_LONGITUDE),
                node(CENTER_LATITUDE + 0.002, CENTER_LONGITUDE + TILE_WIDTH_DEGREES));

        Coordinate[] western = onlyLine(unbuffered.buildTile(graph, ZOOM, TILE_X, TILE_Y)).getCoordinates();
        Coordinate[] eastern = onlyLine(unbuffered.buildTile(graph, ZOOM, TILE_X + 1, TILE_Y)).getCoordinates();

        assertCoordinates(western, 2048, 2594, 4096, 2048);
        assertCoordinates(eastern, 0, 2048, 2048, 1502);
        // the crossing point is one place: x differs by exactly one extent, y is identical
        assertEquals(SlippyMap.TILE_EXTENT, (int) (western[1].x - eastern[0].x));
        assertEquals(western[1].y, eastern[0].y);
    }

    @Test
    void edgeOutsideTheTileAndBufferAddsNoFeature() throws IOException {
        RouteGraph graph = new RouteGraph();
        addEdge(graph,
                node(CENTER_LATITUDE, CENTER_LONGITUDE),
                node(CENTER_LATITUDE, CENTER_LONGITUDE + TILE_WIDTH_DEGREES));
        // a quarter of the world east of the fixture tile: neither in it nor in its buffer
        addEdge(graph,
                node(CENTER_LATITUDE, CENTER_LONGITUDE + 0.25),
                node(CENTER_LATITUDE, CENTER_LONGITUDE + 0.25 + TILE_WIDTH_DEGREES));

        List<LineString> lines = lines(unbuffered.buildTile(graph, ZOOM, TILE_X, TILE_Y));

        assertEquals(1, lines.size());
        assertCoordinates(lines.get(0), 2048, 2048, 4096, 2048);
    }

    @Test
    void bufferKeepsTheClippedStubOfAnEdgeJustOutsideTheTile() throws IOException {
        // a vertical edge 0.0002° east of the eastern boundary, i.e. ~37 units outside:
        // x = 4133, y = 3072 (south) -> 1024 (north)
        double justOutside = BOUNDARY_LONGITUDE + 0.0002;
        RouteGraph graph = graphWith(
                node(CENTER_LATITUDE - 0.00375, justOutside),
                node(CENTER_LATITUDE + 0.00375, justOutside));

        assertCoordinates(onlyLine(builder.buildTile(graph, ZOOM, TILE_X, TILE_Y)), 4133, 3072, 4133, 1024);
        // the same edge is outside the unbuffered tile box
        assertArrayEquals(new byte[0], new TileBuilder(0).buildTile(graph, ZOOM, TILE_X, TILE_Y));
    }

    @Test
    void consecutivePointsCollapsingToTheSameTileCoordinateBecomeOnePoint() throws IOException {
        // 1e-7° of longitude is 0.019 units at this zoom: three sub-unit steps collapse
        RouteGraph graph = graphWith(
                node(CENTER_LATITUDE, CENTER_LONGITUDE),
                node(CENTER_LATITUDE, CENTER_LONGITUDE + 1e-7),
                node(CENTER_LATITUDE, CENTER_LONGITUDE + 2e-7),
                node(CENTER_LATITUDE, CENTER_LONGITUDE + 3e-7),
                node(CENTER_LATITUDE, CENTER_LONGITUDE + TILE_WIDTH_DEGREES / 4));

        assertCoordinates(onlyLine(builder.buildTile(graph, ZOOM, TILE_X, TILE_Y)), 2048, 2048, 3072, 2048);
    }

    @Test
    void fullyCollapsedEdgeProducesNoFeature() throws IOException {
        RouteGraph withCollapsedEdge = graphWith(
                node(CENTER_LATITUDE, CENTER_LONGITUDE),
                node(CENTER_LATITUDE + 1e-7, CENTER_LONGITUDE + 1e-7),
                node(CENTER_LATITUDE + 2e-7, CENTER_LONGITUDE + 2e-7));
        RouteGraph withRealEdge = graphWith(
                node(CENTER_LATITUDE, CENTER_LONGITUDE),
                node(CENTER_LATITUDE, CENTER_LONGITUDE + TILE_WIDTH_DEGREES));

        assertEquals(0, builder.buildTile(withCollapsedEdge, ZOOM, TILE_X, TILE_Y).length);
        assertEquals(1, lines(builder.buildTile(withRealEdge, ZOOM, TILE_X, TILE_Y)).size());
    }

    @Test
    void tileWithoutAnyEdgeIsAValidTileWithZeroLayers() throws IOException {
        byte[] tile = builder.buildTile(new RouteGraph(), ZOOM, TILE_X, TILE_Y);

        assertEquals(0, tile.length);
        assertEquals(0, VectorTile.Tile.parseFrom(tile).getLayersCount());
    }

    @Test
    void junctionIsAPointWhereItsEdgesEnd() throws IOException {
        byte[] tile = builder.buildTile(crossingGraph(), ZOOM, TILE_X, TILE_Y);

        List<Point> dots = junctionPoints(tile);
        List<LineString> lines = lines(tile);

        assertEquals(1, dots.size(), "one dot for the junction, none for the route ends");
        assertEquals(2048, (int) dots.get(0).getX(), "the crossing is the tile centre");
        assertEquals(2048, (int) dots.get(0).getY(), "the crossing is the tile centre");
        assertEquals(4, lines.size(), "the crossing cuts both routes in two");
        for (LineString line : lines) {
            assertTrue(Arrays.stream(line.getCoordinates())
                            .anyMatch(coordinate -> coordinate.x == 2048 && coordinate.y == 2048),
                    "every incident edge path ends at the junction dot");
        }
    }

    @Test
    void junctionOutsideTheTileAndBufferAddsNoDotAndNoLayer() throws IOException {
        // four tiles east: the crossing, its spokes and every edge path are far outside the box
        byte[] tile = builder.buildTile(crossingGraph(), ZOOM, TILE_X + 4, TILE_Y);

        assertEquals(0, tile.length);
        assertTrue(junctionPoints(tile).isEmpty());
    }

    @Test
    void clippedGeometryCarriesEdgeProperties() throws IOException {
        RouteGraph graph = sharedEdgeGraph();
        GraphEdge edge = graph.getEdges().get(0);

        LineString line = onlyLine(builder.buildTile(graph, ZOOM, TILE_X, TILE_Y));

        assertEquals(Map.of(
                "traversals", 2L,
                "lengthMeters", edge.getLengthMeters(),
                "shared", true,
                "routeCount", 2L), line.getUserData());
    }

    @Test
    void rejectsAddressesOutsideTheZoomGrid() {
        RouteGraph graph = graphWith(
                node(CENTER_LATITUDE, CENTER_LONGITUDE),
                node(CENTER_LATITUDE, CENTER_LONGITUDE + TILE_WIDTH_DEGREES));

        assertThrows(IllegalArgumentException.class, () -> builder.buildTile(graph, 23, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> builder.buildTile(graph, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> builder.buildTile(graph, ZOOM, -1, TILE_Y));
        assertThrows(IllegalArgumentException.class, () -> builder.buildTile(graph, ZOOM, 1 << ZOOM, TILE_Y));
        assertThrows(IllegalArgumentException.class, () -> builder.buildTile(graph, ZOOM, TILE_X, 1 << ZOOM));
        assertThrows(IllegalArgumentException.class, () -> new TileBuilder(-1));
    }

    private static Node node(double latitude, double longitude) {
        return new Node(latitude, longitude);
    }

    /** A graph holding one edge along the given path, traveled by one route. */
    private static RouteGraph graphWith(Node... path) {
        RouteGraph graph = new RouteGraph();
        addEdge(graph, path);
        return graph;
    }

    /**
     * The crossing fixture edge traveled by two routes: the builder merges them
     * into one edge with {@code traversals = 2} and {@code routeCount = 2}.
     */
    private static RouteGraph sharedEdgeGraph() {
        List<Node> path = List.of(
                node(CENTER_LATITUDE, CENTER_LONGITUDE),
                node(CENTER_LATITUDE, CENTER_LONGITUDE + TILE_WIDTH_DEGREES));
        List<Route> routes = List.of(route("route-a", path), route("route-b", path));

        RouteGraph graph = new GraphBuilder(10.0).build(routes, List.of());

        assertEquals(1, graph.getEdges().size(), "both routes must share the edge");
        return graph;
    }

    /**
     * Two routes crossing at the fixture tile's centre, both carrying a track
     * point there, so the real pipeline finds the junction: it sits at the
     * crossing, and each of the four edge paths was cut at its 10 m circle and
     * ends at the junction coordinate.
     */
    private static RouteGraph crossingGraph() {
        List<Route> routes = List.of(
                route("route-a", List.of(
                        node(CENTER_LATITUDE, CENTER_LONGITUDE - TILE_WIDTH_DEGREES / 4),
                        node(CENTER_LATITUDE, CENTER_LONGITUDE),
                        node(CENTER_LATITUDE, CENTER_LONGITUDE + TILE_WIDTH_DEGREES / 4))),
                route("route-b", List.of(
                        node(CENTER_LATITUDE + 0.00375, CENTER_LONGITUDE),
                        node(CENTER_LATITUDE, CENTER_LONGITUDE),
                        node(CENTER_LATITUDE - 0.00375, CENTER_LONGITUDE))));
        GraphBuilder graphBuilder = new GraphBuilder(10.0);
        return graphBuilder.simplify(graphBuilder.build(routes, new IntersectionFinder(10.0).find(routes)));
    }

    private static Route route(String name, List<Node> path) {
        Route route = new Route(name);
        path.forEach(route::addNode);
        return route;
    }

    private static void addEdge(RouteGraph graph, Node... path) {
        Route route = new Route("route-a");
        for (Node node : path) {
            route.addNode(node);
        }
        GraphNode from = graph.addEndpoint(path[0]);
        GraphNode to = graph.addEndpoint(path[path.length - 1]);
        graph.addEdge(new GraphEdge(from, to, List.of(path), route));
    }

    private static List<LineString> lines(byte[] tile) throws IOException {
        JtsLayer layer = decode(tile).getLayer(TileBuilder.LAYER_NAME);
        assertNotNull(layer, "tile has no " + TileBuilder.LAYER_NAME + " layer");
        List<LineString> lines = new ArrayList<>();
        for (Geometry geometry : layer.getGeometries()) {
            lines.add((LineString) geometry);
        }
        return lines;
    }

    /** The decoded junction dots; empty when the tile carries no {@code junctions} layer. */
    private static List<Point> junctionPoints(byte[] tile) throws IOException {
        JtsLayer layer = decode(tile).getLayer(TileBuilder.JUNCTION_LAYER_NAME);
        if (layer == null) {
            return List.of();
        }
        List<Point> points = new ArrayList<>();
        for (Geometry geometry : layer.getGeometries()) {
            points.add((Point) geometry);
        }
        return points;
    }

    private static JtsMvt decode(byte[] tile) throws IOException {
        return MvtReader.loadMvt(new ByteArrayInputStream(tile), new GeometryFactory(),
                new TagKeyValueMapConverter(true));
    }

    private static LineString onlyLine(byte[] tile) throws IOException {
        List<LineString> lines = lines(tile);
        assertEquals(1, lines.size(), "expected exactly one feature");
        return lines.get(0);
    }

    /** Asserts the decoded coordinates, given as x, y pairs. */
    private static void assertCoordinates(LineString line, int... expected) {
        assertCoordinates(line.getCoordinates(), expected);
    }

    private static void assertCoordinates(Coordinate[] actual, int... expected) {
        assertEquals(expected.length / 2, actual.length, "point count");
        for (int i = 0; i < expected.length; i += 2) {
            int point = i / 2;
            assertEquals(expected[i], (int) actual[point].x, "x of point " + point);
            assertEquals(expected[i + 1], (int) actual[point].y, "y of point " + point);
        }
    }
}
