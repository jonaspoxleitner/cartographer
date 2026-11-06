package com.cartographer.server.tiles;

import com.cartographer.server.tiles.MvtEncoder.MvtValue;
import com.cartographer.server.tiles.MvtEncoder.TilePoint;
import com.wdtinc.mapbox_vector_tile.VectorTile;
import com.wdtinc.mapbox_vector_tile.adapt.jts.MvtReader;
import com.wdtinc.mapbox_vector_tile.adapt.jts.TagKeyValueMapConverter;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsLayer;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsMvt;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the hand-written protobuf/MVT encoding byte for byte and round-trips a
 * tile through the test-scope {@code mapbox-vector-tile} decoder.
 */
class MvtEncoderTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void varintMatchesProtobufWireFormat() {
        assertVarint("00", 0);
        assertVarint("01", 1);
        assertVarint("7f", 127);
        assertVarint("8001", 128);
        assertVarint("ac02", 300);
        assertVarint("8020", 4096);
        assertVarint("ffffffffffffffffff01", -1);
    }

    @Test
    void zigZagMapsSignedDeltasToUnsigned() {
        assertEquals(0, MvtEncoder.zigZag(0));
        assertEquals(1, MvtEncoder.zigZag(-1));
        assertEquals(2, MvtEncoder.zigZag(1));
        assertEquals(3, MvtEncoder.zigZag(-2));
        assertEquals(4, MvtEncoder.zigZag(2));
        assertEquals(127, MvtEncoder.zigZag(-64));
        assertEquals(128, MvtEncoder.zigZag(64));
    }

    @Test
    void lineStringCommandsFollowTheSpecExample() {
        // MVT spec 2.1 works example: [(2,2), (2,10), (10,10)] -> [9, 4, 4, 18, 0, 16, 16, 0]
        int[] commands = MvtEncoder.lineStringCommands(List.of(
                new TilePoint(2, 2), new TilePoint(2, 10), new TilePoint(10, 10)));

        assertArrayEquals(new int[] {9, 4, 4, 18, 0, 16, 16, 0}, commands);
    }

    @Test
    void encodesMinimalTileByteForByte() {
        MvtEncoder encoder = new MvtEncoder("l", SlippyMap.TILE_EXTENT);
        encoder.addFeature(List.of(new TilePoint(2, 2), new TilePoint(2, 10), new TilePoint(10, 10)),
                Map.of());

        // Tile.layers { Layer {
        //   name "l", Feature { type LINESTRING, geometry [9,4,4,18,0,16,16,0] },
        //   extent 4096, version 2 } }
        assertEquals("1a160a016c120c1802220809040412001010002880207802",
                HEX.formatHex(encoder.toByteArray()));
    }

    @Test
    void decoderOracleRoundTripsGeometryAndProperties() throws IOException {
        MvtEncoder encoder = new MvtEncoder("edges", SlippyMap.TILE_EXTENT);
        Map<String, MvtValue> properties = new LinkedHashMap<>();
        properties.put("traversals", MvtValue.of(3));
        properties.put("lengthMeters", MvtValue.of(1234.5));
        properties.put("shared", MvtValue.of(true));
        properties.put("routeCount", MvtValue.of(2));
        properties.put("routeName", MvtValue.of("route-a"));
        encoder.addFeature(List.of(
                new TilePoint(10, 20), new TilePoint(30, 40), new TilePoint(50, 60)), properties);
        byte[] tile = encoder.toByteArray();

        VectorTile.Tile parsed = VectorTile.Tile.parseFrom(tile);
        assertEquals(1, parsed.getLayersCount());
        VectorTile.Tile.Layer layer = parsed.getLayers(0);
        assertEquals("edges", layer.getName());
        assertEquals(2, layer.getVersion());
        assertEquals(SlippyMap.TILE_EXTENT, layer.getExtent());
        assertEquals(5, layer.getKeysCount());
        assertEquals(5, layer.getValuesCount());

        JtsMvt decoded = MvtReader.loadMvt(new ByteArrayInputStream(tile),
                new GeometryFactory(), new TagKeyValueMapConverter(true));
        JtsLayer decodedLayer = decoded.getLayer("edges");
        assertEquals(SlippyMap.TILE_EXTENT, decodedLayer.getExtent());
        assertEquals(1, decodedLayer.getGeometries().size());

        LineString line = (LineString) decodedLayer.getGeometries().iterator().next();
        assertArrayEquals(new Coordinate[] {
                new Coordinate(10, 20), new Coordinate(30, 40), new Coordinate(50, 60),
        }, line.getCoordinates());
        assertEquals(Map.of(
                "traversals", 3L,
                "lengthMeters", 1234.5,
                "shared", true,
                "routeCount", 2L,
                "routeName", "route-a"), line.getUserData());
    }

    @Test
    void equalPropertyValuesShareOneValueTableEntry() throws IOException {
        MvtEncoder encoder = new MvtEncoder("edges", SlippyMap.TILE_EXTENT);
        encoder.addFeature(List.of(new TilePoint(0, 0), new TilePoint(1, 1)),
                Map.of("traversals", MvtValue.of(2)));
        encoder.addFeature(List.of(new TilePoint(2, 2), new TilePoint(3, 3)),
                Map.of("traversals", MvtValue.of(2)));

        VectorTile.Tile.Layer layer = VectorTile.Tile.parseFrom(encoder.toByteArray()).getLayers(0);

        assertEquals(2, layer.getFeaturesCount());
        assertEquals(1, layer.getKeysCount());
        assertEquals(1, layer.getValuesCount());
        assertEquals(2, layer.getFeatures(0).getTagsCount());
        assertEquals(layer.getFeatures(0).getTagsList(), layer.getFeatures(1).getTagsList());
    }

    @Test
    void rejectsDegenerateFeatureAndInvalidLayer() {
        MvtEncoder encoder = new MvtEncoder("edges", SlippyMap.TILE_EXTENT);

        assertThrows(IllegalArgumentException.class,
                () -> encoder.addFeature(List.of(new TilePoint(1, 1)), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new MvtEncoder(" ", SlippyMap.TILE_EXTENT));
        assertThrows(IllegalArgumentException.class, () -> new MvtEncoder("edges", 0));
        assertTrue(encoder.toByteArray().length > 0);
    }

    private static void assertVarint(String expectedHex, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MvtEncoder.writeVarint(out, value);
        assertEquals(expectedHex, HEX.formatHex(out.toByteArray()));
    }
}
