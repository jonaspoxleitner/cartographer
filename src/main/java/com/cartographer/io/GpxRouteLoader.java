package com.cartographer.io;

import com.cartographer.model.Node;
import com.cartographer.model.Route;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads GPX 1.x files into {@link Route} objects.
 * Track points (trkpt) become the route's path nodes,
 * waypoints (wpt) become named waypoints.
 */
public class GpxRouteLoader {

    public Route load(Path gpxFile) throws IOException {
        if (gpxFile == null || !Files.isRegularFile(gpxFile)) {
            throw new IOException("GPX file not found: " + gpxFile);
        }
        try (InputStream in = Files.newInputStream(gpxFile)) {
            return load(in);
        }
    }

    public Route load(InputStream in) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Harden against XXE attacks on untrusted GPX files
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);

            Document doc = factory.newDocumentBuilder().parse(in);
            return parseRoute(doc);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse GPX file", e);
        }
    }

    private Route parseRoute(Document doc) {
        Route route = new Route();

        Element root = doc.getDocumentElement();
        NodeList names = root.getElementsByTagNameNS("*", "name");
        for (int i = 0; i < names.getLength(); i++) {
            Element name = (Element) names.item(i);
            route.setName(name.getTextContent().trim());
            break;
        }

        NodeList waypoints = root.getElementsByTagNameNS("*", "wpt");
        for (int i = 0; i < waypoints.getLength(); i++) {
            Element wpt = (Element) waypoints.item(i);
            route.addWaypoint(parsePoint(wpt, true));
        }

        NodeList trackPoints = root.getElementsByTagNameNS("*", "trkpt");
        for (int i = 0; i < trackPoints.getLength(); i++) {
            route.addNode(parsePoint((Element) trackPoints.item(i), false));
        }

        return route;
    }

    private Node parsePoint(Element point, boolean withName) {
        double lat = Double.parseDouble(point.getAttribute("lat"));
        double lon = Double.parseDouble(point.getAttribute("lon"));
        Double elevation = childText(point, "ele").map(Double::parseDouble).orElse(null);
        String name = withName && point != null
                ? childText(point, "name").orElse(null)
                : null;
        return new Node(lat, lon, elevation, name);
    }

    private java.util.Optional<String> childText(Element parent, String tag) {
        NodeList children = parent.getElementsByTagNameNS("*", tag);
        if (children.getLength() == 0) {
            // Fall back for files without a namespace
            children = parent.getElementsByTagName(tag);
        }
        if (children.getLength() == 0) {
            return java.util.Optional.empty();
        }
        String text = children.item(0).getTextContent();
        return text == null || text.isBlank()
                ? java.util.Optional.empty()
                : java.util.Optional.of(text.trim());
    }
}
