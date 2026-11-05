package com.cartographer.map;

import com.cartographer.model.Node;
import com.cartographer.model.Route;

/**
 * A reference to a specific node on a specific route.
 */
public record RoutePoint(Route route, int index, Node node) {
}
