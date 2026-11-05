package com.cartographer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Settings read from {@code application.properties}; no Spring Boot involved. */
@Component
public class CartographerProperties {

    private final double intersectionToleranceMeters;
    private final Path routesDir;
    private final Path svgOutput;

    public CartographerProperties(
            @Value("${cartographer.intersection-tolerance-meters}") double intersectionToleranceMeters,
            @Value("${cartographer.routes-dir}") String routesDir,
            @Value("${cartographer.svg-output}") String svgOutput) {
        this.intersectionToleranceMeters = intersectionToleranceMeters;
        this.routesDir = Path.of(routesDir);
        this.svgOutput = Path.of(svgOutput);
    }

    /** Two tracks cross when they pass within this many meters of each other. */
    public double intersectionToleranceMeters() {
        return intersectionToleranceMeters;
    }

    /** Directory scanned for {@code .gpx} files when no files are given on the CLI. */
    public Path routesDir() {
        return routesDir;
    }

    /** Where the SVG map is written. */
    public Path svgOutput() {
        return svgOutput;
    }
}
