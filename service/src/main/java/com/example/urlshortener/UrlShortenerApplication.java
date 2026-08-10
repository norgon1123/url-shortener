package com.example.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point.
 *
 * <p>This class, the cache configuration beside it, and the build files are the
 * hand-built scaffold. Everything below it in the package tree -- controllers,
 * services, entities, migrations -- is produced by orchestrator runs, and each
 * of those commits carries {@code Run-Id} and {@code Node-Id} trailers, so
 * {@code git log} distinguishes the two without anyone having to take a claim
 * about provenance on trust.
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
@ConfigurationPropertiesScan
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
