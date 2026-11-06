package com.cartographer.server.config;

import com.cartographer.io.GpxRouteLoader;
import com.cartographer.map.GraphBuilder;
import com.cartographer.map.IntersectionFinder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the graph-building pipeline. The component scan is rooted in
 * {@code com.cartographer.server}, so core's components are not picked up
 * implicitly: the loader is declared here, and the tolerance-dependent beans
 * are built here because their tolerance is a constructor argument.
 */
@Configuration
@EnableConfigurationProperties(ServerProperties.class)
public class ServerConfig {

    @Bean
    public GpxRouteLoader gpxRouteLoader() {
        return new GpxRouteLoader();
    }

    @Bean
    public IntersectionFinder intersectionFinder(ServerProperties properties) {
        return new IntersectionFinder(properties.intersectionToleranceMeters());
    }

    @Bean
    public GraphBuilder graphBuilder(ServerProperties properties) {
        return new GraphBuilder(properties.intersectionToleranceMeters());
    }
}
