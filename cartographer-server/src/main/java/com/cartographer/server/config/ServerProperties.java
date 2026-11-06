package com.cartographer.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Server settings read from {@code application.properties}. Same keys as the
 * CLI, but the server does not render SVG, so there is no svg-output, and the
 * tile endpoints add the clipping buffer. Paths resolve against the working
 * directory: the server is started from the repository root.
 */
@ConfigurationProperties("cartographer")
public record ServerProperties(double intersectionToleranceMeters, Path routesDir, int tileBufferUnits) {
}
