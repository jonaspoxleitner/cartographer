package com.cartographer;

import com.cartographer.config.AppConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;

public class App {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(AppConfig.class)) {
            context.getBean(CartographerRunner.class).run(args);
        } catch (IOException e) {
            System.err.println("Could not load routes: " + e.getMessage());
            System.exit(1);
        }
    }
}
