package com.cartographer.config;

import com.cartographer.CartographerRunner;
import com.cartographer.io.GpxRouteLoader;
import com.cartographer.map.GraphBuilder;
import com.cartographer.map.IntersectionFinder;
import com.cartographer.ui.SvgGraphRenderer;
import com.cartographer.ui.TextGraphRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Smoke test: the application context boots and holds the wired pipeline. */
@SpringJUnitConfig(AppConfig.class)
class ApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CartographerProperties properties;

    @Test
    void bootsWithPipelineBeans() {
        assertNotNull(context.getBean(GpxRouteLoader.class));
        assertNotNull(context.getBean(IntersectionFinder.class));
        assertNotNull(context.getBean(GraphBuilder.class));
        assertNotNull(context.getBean(TextGraphRenderer.class));
        assertNotNull(context.getBean(SvgGraphRenderer.class));
        assertNotNull(context.getBean(CartographerRunner.class));
    }

    @Test
    void readsSettingsFromApplicationProperties() {
        Environment environment = context.getEnvironment();
        assertEquals(
                environment.getProperty("cartographer.intersection-tolerance-meters", Double.class),
                properties.intersectionToleranceMeters());
        assertEquals(Path.of(environment.getProperty("cartographer.routes-dir")),
                properties.routesDir());
        assertEquals(Path.of(environment.getProperty("cartographer.svg-output")),
                properties.svgOutput());
    }
}
