package com.cartographer.server.tiles;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written Mapbox Vector Tile encoder (spec 2.1): the protobuf wire format
 * and the geometry command encoding, with no tile library on the runtime
 * classpath.
 *
 * <p>One instance serialises one layer: the layer name, features and their
 * property tables. Features are added in tile-local coordinates
 * ({@code 0..extent}, y growing southward; the clipping buffer may push
 * coordinates slightly past the extent). {@link #tile(List)} assembles the
 * layers of several encoders into one tile. An empty tile has zero layers and
 * is therefore the empty byte array rather than the output of this class.
 */
public final class MvtEncoder {

    /** MVT spec version written into the layer. */
    private static final int VERSION = 2;

    // Wire types (https://protobuf.dev/programming-guides/encoding/#structure)
    private static final int WIRE_VARINT = 0;
    private static final int WIRE_64_BIT = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;

    // Field numbers from vector_tile.proto: Tile, Layer, Feature, Value
    private static final int TILE_LAYERS = 3;
    private static final int LAYER_NAME = 1;
    private static final int LAYER_FEATURES = 2;
    private static final int LAYER_KEYS = 3;
    private static final int LAYER_VALUES = 4;
    private static final int LAYER_EXTENT = 5;
    private static final int LAYER_VERSION = 15;
    private static final int FEATURE_TAGS = 2;
    private static final int FEATURE_TYPE = 3;
    private static final int FEATURE_GEOMETRY = 4;
    private static final int VALUE_STRING = 1;
    private static final int VALUE_DOUBLE = 3;
    private static final int VALUE_INT = 4;
    private static final int VALUE_BOOL = 7;

    /** GeomType.POINT. */
    private static final int GEOMETRY_POINT = 1;
    /** GeomType.LINESTRING. */
    private static final int GEOMETRY_LINESTRING = 2;
    /** Geometry command ids from the MVT spec. */
    private static final int COMMAND_MOVE_TO = 1;
    private static final int COMMAND_LINE_TO = 2;

    private final String layerName;
    private final int extent;
    private final List<Feature> features = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private final Map<String, Integer> keyIndexes = new HashMap<>();
    private final List<MvtValue> values = new ArrayList<>();
    private final Map<MvtValue, Integer> valueIndexes = new HashMap<>();

    public MvtEncoder(String layerName, int extent) {
        if (layerName == null || layerName.isBlank()) {
            throw new IllegalArgumentException("layer name must not be blank");
        }
        if (extent <= 0) {
            throw new IllegalArgumentException("extent must be positive: " + extent);
        }
        this.layerName = layerName;
        this.extent = extent;
    }

    /**
     * Adds one line feature. The points form a single path in tile-local
     * coordinates and must contain at least two points; degenerate features are
     * the caller's job to drop. Property values are interned into the layer's
     * key/value tables.
     */
    public void addFeature(List<TilePoint> points, Map<String, MvtValue> properties) {
        if (points.size() < 2) {
            throw new IllegalArgumentException(
                    "a line feature needs at least two points, got " + points.size());
        }
        features.add(new Feature(GEOMETRY_LINESTRING, List.copyOf(points), tags(properties)));
    }

    /**
     * Adds one point feature at a tile-local coordinate, for a symbol that marks
     * a place rather than a path. Property values are interned into the layer's
     * key/value tables, like a line feature's.
     */
    public void addPointFeature(TilePoint point, Map<String, MvtValue> properties) {
        features.add(new Feature(GEOMETRY_POINT, List.of(point), tags(properties)));
    }

    private List<Integer> tags(Map<String, MvtValue> properties) {
        List<Integer> tags = new ArrayList<>(properties.size() * 2);
        for (Map.Entry<String, MvtValue> property : properties.entrySet()) {
            tags.add(keyIndex(property.getKey()));
            tags.add(valueIndex(property.getValue()));
        }
        return List.copyOf(tags);
    }

    /** Serialises the tile: this encoder's one {@code Tile.Layer} wrapped in the tile's bytes. */
    public byte[] toByteArray() {
        ByteArrayOutputStream tile = new ByteArrayOutputStream();
        writeMessageField(tile, TILE_LAYERS, layerBytes());
        return tile.toByteArray();
    }

    /**
     * Serialises several single-layer encoders into one tile: one
     * {@code Tile.Layer} message per encoder, in the given order, so a tile can
     * carry paths and the symbols that belong to them.
     */
    public static byte[] tile(List<MvtEncoder> layers) {
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("a tile needs at least one layer");
        }
        ByteArrayOutputStream tile = new ByteArrayOutputStream();
        for (MvtEncoder layer : layers) {
            writeMessageField(tile, TILE_LAYERS, layer.layerBytes());
        }
        return tile.toByteArray();
    }

    private byte[] layerBytes() {
        ByteArrayOutputStream layer = new ByteArrayOutputStream();
        writeStringField(layer, LAYER_NAME, layerName);
        for (Feature feature : features) {
            writeMessageField(layer, LAYER_FEATURES, encodeFeature(feature));
        }
        for (String key : keys) {
            writeStringField(layer, LAYER_KEYS, key);
        }
        for (MvtValue value : values) {
            writeMessageField(layer, LAYER_VALUES, encodeValue(value));
        }
        writeVarintField(layer, LAYER_EXTENT, extent);
        writeVarintField(layer, LAYER_VERSION, VERSION);
        return layer.toByteArray();
    }

    private int keyIndex(String key) {
        return keyIndexes.computeIfAbsent(key, name -> {
            keys.add(name);
            return keys.size() - 1;
        });
    }

    private int valueIndex(MvtValue value) {
        return valueIndexes.computeIfAbsent(value, entry -> {
            values.add(entry);
            return values.size() - 1;
        });
    }

    private static byte[] encodeFeature(Feature feature) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] tags = feature.tags().stream().mapToInt(Integer::intValue).toArray();
        writePackedVarints(body, FEATURE_TAGS, tags);
        writeVarintField(body, FEATURE_TYPE, feature.geometryType());
        writePackedVarints(body, FEATURE_GEOMETRY, feature.geometryType() == GEOMETRY_POINT
                ? pointCommands(feature.points().getFirst())
                : lineStringCommands(feature.points()));
        return body.toByteArray();
    }

    /**
     * Encodes a point as MVT geometry commands: a single MoveTo from the cursor's
     * origin, with no LineTo and nothing to close.
     */
    static int[] pointCommands(TilePoint point) {
        return new int[] {command(COMMAND_MOVE_TO, 1), zigZag(point.x()), zigZag(point.y())};
    }

    private static byte[] encodeValue(MvtValue value) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        switch (value) {
            case MvtValue.TextValue text -> writeStringField(body, VALUE_STRING, text.value());
            case MvtValue.IntValue integer -> writeVarintField(body, VALUE_INT, integer.value());
            case MvtValue.RealValue real -> writeDoubleField(body, VALUE_DOUBLE, real.value());
            case MvtValue.BoolValue flag -> writeVarintField(body, VALUE_BOOL, flag.value() ? 1 : 0);
        }
        return body.toByteArray();
    }

    /**
     * Encodes a line path as MVT geometry commands: one MoveTo for the first
     * point, then a single LineTo for all remaining points, each coordinate a
     * zigzag delta from the previous point.
     */
    static int[] lineStringCommands(List<TilePoint> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("a line path needs at least two points, got " + points.size());
        }
        int[] commands = new int[2 + 2 * points.size()];
        int index = 0;
        TilePoint cursor = points.getFirst();
        commands[index++] = command(COMMAND_MOVE_TO, 1);
        commands[index++] = zigZag(cursor.x());
        commands[index++] = zigZag(cursor.y());
        commands[index++] = command(COMMAND_LINE_TO, points.size() - 1);
        for (int i = 1; i < points.size(); i++) {
            TilePoint next = points.get(i);
            commands[index++] = zigZag(next.x() - cursor.x());
            commands[index++] = zigZag(next.y() - cursor.y());
            cursor = next;
        }
        return commands;
    }

    /** A geometry command header: {@code (count << 3) | commandId}. */
    private static int command(int id, int count) {
        return (count << 3) | id;
    }

    static int zigZag(int value) {
        return (value << 1) ^ (value >> 31);
    }

    static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void writeTag(ByteArrayOutputStream out, int fieldNumber, int wireType) {
        writeVarint(out, ((long) fieldNumber << 3) | wireType);
    }

    private static void writeVarintField(ByteArrayOutputStream out, int fieldNumber, long value) {
        writeTag(out, fieldNumber, WIRE_VARINT);
        writeVarint(out, value);
    }

    private static void writeStringField(ByteArrayOutputStream out, int fieldNumber, String value) {
        writeMessageField(out, fieldNumber, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeDoubleField(ByteArrayOutputStream out, int fieldNumber, double value) {
        writeTag(out, fieldNumber, WIRE_64_BIT);
        long bits = Double.doubleToRawLongBits(value);
        for (int shift = 0; shift < 64; shift += 8) {
            out.write((int) (bits >>> shift) & 0xFF);
        }
    }

    private static void writeMessageField(ByteArrayOutputStream out, int fieldNumber, byte[] payload) {
        writeTag(out, fieldNumber, WIRE_LENGTH_DELIMITED);
        writeVarint(out, payload.length);
        out.writeBytes(payload);
    }

    private static void writePackedVarints(ByteArrayOutputStream out, int fieldNumber, int[] values) {
        if (values.length == 0) {
            return;
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int value : values) {
            writeVarint(payload, value);
        }
        writeMessageField(out, fieldNumber, payload.toByteArray());
    }

    /** A tile-local point; x grows east, y grows south. */
    public record TilePoint(int x, int y) {
    }

    /** A feature's geometry type, its points and its interleaved key/value table indexes. */
    private record Feature(int geometryType, List<TilePoint> points, List<Integer> tags) {
    }

    /**
     * A property value in one of the MVT value types this encoder writes:
     * string, int64, double or bool. Use the {@code of} factories so equal
     * values share a single entry in the layer's value table.
     */
    public sealed interface MvtValue {

        static MvtValue of(String value) {
            return new TextValue(value);
        }

        static MvtValue of(long value) {
            return new IntValue(value);
        }

        static MvtValue of(double value) {
            return new RealValue(value);
        }

        static MvtValue of(boolean value) {
            return new BoolValue(value);
        }

        record TextValue(String value) implements MvtValue {
        }

        record IntValue(long value) implements MvtValue {
        }

        record RealValue(double value) implements MvtValue {
        }

        record BoolValue(boolean value) implements MvtValue {
        }
    }
}
