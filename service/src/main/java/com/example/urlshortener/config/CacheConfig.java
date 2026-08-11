package com.example.urlshortener.config;

import com.example.urlshortener.redirect.CachedLink;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Set;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Caching for the redirect path.
 *
 * <p>The redirect is the only hot path in a shortener -- reads outnumber writes
 * by orders of magnitude and every one of them is a primary-key lookup.
 *
 * <p><strong>Redis rather than the Caffeine this class used to build.</strong>
 * The published takedown bound (60 seconds from a delete or an abuse report) is
 * delivered by <em>actively invalidating</em> the entry, not by waiting out a
 * TTL. A per-instance cache cannot be invalidated from the instance that handled
 * the delete, so on more than one replica the bound would silently rest on the
 * TTL alone -- and no test on a single instance can see that. The TTL configured
 * here is the floor under the bound, not the mechanism.
 *
 * <p>Exactly one {@link CacheManager} bean exists in this application and this
 * method is it. A second candidate fails context load for every integration test
 * at once, which reads like a broken harness rather than a bean conflict.
 *
 * <p>Values are {@link CachedLink}, which carries its own "this code resolves to
 * nothing" form. Negative entries are what keep an enumeration sweep off
 * PostgreSQL, and giving them a representable value rather than a null lets the
 * cache be typed to one class -- no polymorphic type information on the wire and
 * no null-marker handling.
 */
@Configuration
public class CacheConfig {

    /** Caches negative lookups too -- see the enumeration note in ADR-004. */
    public static final String LINKS_CACHE = "links";

    private final AppProperties.Cache settings;
    private final RedisConnectionFactory connectionFactory;

    public CacheConfig(AppProperties properties, RedisConnectionFactory connectionFactory) {
        this.settings = properties.cache();
        this.connectionFactory = connectionFactory;
    }

    @Bean
    public CacheManager cacheManager() {
        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(settings.ttl())
                .disableCachingNullValues()
                // One flat prefix instead of Spring's "cacheName::" so the keys are
                // the app.cache.key-prefix the configuration documents.
                .computePrefixWith(cacheName -> settings.keyPrefix())
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(SerializationPair.fromSerializer(valueSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(configuration)
                .initialCacheNames(Set.of(LINKS_CACHE))
                .build();
    }

    private static Jackson2JsonRedisSerializer<CachedLink> valueSerializer() {
        return new Jackson2JsonRedisSerializer<>(
                JsonMapper.builder()
                        .addModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .build(),
                CachedLink.class);
    }
}
