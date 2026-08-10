package com.example.urlshortener.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-process caching for the redirect path.
 *
 * <p>The redirect is the only hot path in a shortener -- reads outnumber writes
 * by orders of magnitude and every one of them is a primary-key lookup. Caffeine
 * serves those without a network hop. Redis would add one, plus an operational
 * dependency, in exchange for cross-instance sharing that a single instance does
 * not need; {@code docs/architecture.md} records it as the scale path rather
 * than pretending the decision was never available.
 *
 * <p>The spec is externalised so the TTL can be tuned per environment without a
 * rebuild. Bounding both size and lifetime is the point: an unbounded cache in
 * front of a table anyone on the internet can add rows to is a memory-exhaustion
 * vector, not an optimisation.
 */
@Configuration
public class CacheConfig {

    /** Caches negative lookups too -- see the enumeration note in ADR-004. */
    public static final String LINKS_CACHE = "links";

    private final String spec;

    public CacheConfig(@Value("${app.cache.spec:maximumSize=10000,expireAfterWrite=5m}") String spec) {
        this.spec = spec;
    }

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(LINKS_CACHE);
        manager.setCaffeine(Caffeine.from(spec));
        return manager;
    }
}
