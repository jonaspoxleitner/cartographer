package com.cartographer.config;

import com.cartographer.map.GraphBuilder;
import com.cartographer.map.IntersectionFinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/**
 * Wires the graph-building pipeline. Stateless components are picked up by
 * the component scan; the two tolerance-dependent ones are built here
 * because their tolerance is a constructor argument.
 */
@Configuration
@ComponentScan("com.cartographer")
@PropertySource("classpath:application.properties")
public class AppConfig {

    /** Resolves the {@code ${...}} placeholders in {@link CartographerProperties}. */
    @Bean
    public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    @Bean
    public IntersectionFinder intersectionFinder(CartographerProperties properties) {
        return new IntersectionFinder(properties.intersectionToleranceMeters());
    }

    @Bean
    public GraphBuilder graphBuilder(CartographerProperties properties) {
        return new GraphBuilder(properties.intersectionToleranceMeters());
    }
}
