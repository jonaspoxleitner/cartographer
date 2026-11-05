package com.cartographer.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeTest {

    @Test
    void rejectsOutOfRangeCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new Node(91, 0));
        assertThrows(IllegalArgumentException.class, () -> new Node(0, 181));
    }

    @Test
    void distanceToSelfIsZero() {
        Node node = new Node(48.601275, 13.787572);
        assertEquals(0, node.distanceTo(node));
    }

    @Test
    void haversineDistanceIsRoughlyCorrect() {
        // ~111 km per degree of latitude
        Node a = new Node(48.0, 13.0);
        Node b = new Node(49.0, 13.0);
        assertEquals(111_000, a.distanceTo(b), 2_000);
    }

    @Test
    void equalityIsBasedOnCoordinates() {
        assertEquals(new Node(48.0, 13.0, null, "A"), new Node(48.0, 13.0));
        assertNotEquals(new Node(48.0, 13.0), new Node(48.0, 13.1));
    }
}
