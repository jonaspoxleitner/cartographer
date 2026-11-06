package com.cartographer.server.api;

import com.cartographer.io.GpxRouteLoader;
import com.cartographer.map.RouteGraph;
import com.cartographer.server.config.ServerProperties;
import com.cartographer.server.graph.GraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Serves the graph as GeoJSON and accepts new routes. An upload is validated
 * before anything is written, so a rejected file cannot poison the routes
 * directory the next startup reads.
 */
@RestController
@RequestMapping("/api")
public class GraphController {

    private static final Logger log = LoggerFactory.getLogger(GraphController.class);

    private static final String GPX_SUFFIX = ".gpx";

    private final GraphService graphService;
    private final GeoJsonMapper geoJsonMapper;
    private final GpxRouteLoader routeLoader;
    private final ServerProperties properties;

    public GraphController(GraphService graphService, GeoJsonMapper geoJsonMapper,
                           GpxRouteLoader routeLoader, ServerProperties properties) {
        this.graphService = graphService;
        this.geoJsonMapper = geoJsonMapper;
        this.routeLoader = routeLoader;
        this.properties = properties;
    }

    @GetMapping(value = "/graph", produces = "application/geo+json")
    public GeoJsonMapper.FeatureCollection graph() {
        return geoJsonMapper.toFeatureCollection(graphService.graph());
    }

    /** Persists the upload next to the existing routes and rebuilds the graph. */
    @PostMapping(value = "/routes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestParam("file") MultipartFile file) {
        String filename = gpxFileName(file.getOriginalFilename());
        byte[] content = readUpload(file);
        requireParseableGpx(content, filename);

        Path target = properties.routesDir().resolve(filename);
        try {
            // exclusive create: a duplicate name must be rejected, never overwritten
            Files.createFile(target);
        } catch (FileAlreadyExistsException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "route already exists: " + filename);
        } catch (IOException ioException) {
            throw new UncheckedIOException("failed to create " + target, ioException);
        }
        try {
            Files.write(target, content);
        } catch (IOException ioException) {
            deleteQuietly(target);
            throw new UncheckedIOException("failed to store " + target, ioException);
        }

        graphService.rebuild();
        RouteGraph graph = graphService.graph();
        log.info("Stored {} and rebuilt the graph ({} edges)", target.toAbsolutePath(), graph.getEdges().size());
        return new UploadResponse(filename, graph.getEdges().size());
    }

    /** The submitted base name only, so a path in the upload cannot leave the routes directory. */
    private String gpxFileName(String submitted) {
        if (submitted == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing file name");
        }
        String baseName = submitted.replace('\\', '/');
        baseName = baseName.substring(baseName.lastIndexOf('/') + 1).strip();
        if (baseName.isEmpty() || baseName.equals(".") || baseName.equals("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid file name: " + submitted);
        }
        if (!baseName.toLowerCase(Locale.ROOT).endsWith(GPX_SUFFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a .gpx file: " + baseName);
        }
        return baseName;
    }

    private byte[] readUpload(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ioException) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unreadable upload: " + ioException.getMessage(),
                    ioException);
        }
    }

    /** Parsed with the same loader the rebuild uses, so an accepted file cannot fail the next rebuild. */
    private void requireParseableGpx(byte[] content, String filename) {
        try (InputStream in = new ByteArrayInputStream(content)) {
            routeLoader.load(in);
        } catch (IOException ioException) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a valid GPX file: " + filename, ioException);
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ioException) {
            log.warn("failed to remove {} after a failed upload", file, ioException);
        }
    }

    /** Ack for a stored upload: the name it was saved under and the rebuilt graph's size. */
    public record UploadResponse(String filename, int edges) {
    }
}
