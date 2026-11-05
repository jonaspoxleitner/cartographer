package com.cartographer.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the server frontend. The component scan is rooted in this
 * package, so core's components (in {@code com.cartographer.io}, {@code .map},
 * {@code .model} and {@code .ui}) are not picked up implicitly.
 */
@SpringBootApplication
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
