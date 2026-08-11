package com.example.urlshortener.redirect;

import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.repository.LinkRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a code into the facts the click path needs, from the cache when it can
 * and from PostgreSQL when it cannot.
 *
 * <p>Both outcomes are cached. Caching the misses is what stops an enumeration
 * sweep turning into one database round trip per guess, which is the load the
 * tight not-found bucket is there to refuse and this is there to survive.
 */
@Component
public class LinkResolver {

    private final LinkRepository links;
    private final ResolutionCache cache;
    private final String domain;
    private final Duration negativeTtl;

    public LinkResolver(LinkRepository links, ResolutionCache cache, AppProperties properties) {
        this.links = links;
        this.cache = cache;
        this.domain = properties.domain();
        this.negativeTtl = properties.cache().negativeTtl();
    }

    /** Never null: a code that resolves to nothing comes back as an absent entry. */
    @Transactional(readOnly = true)
    public CachedLink resolve(String code) {
        Instant now = Instant.now();

        Optional<CachedLink> cached = cache.get(code);
        if (cached.isPresent() && isStillValid(cached.get(), now)) {
            return cached.get();
        }

        CachedLink loaded = links.findByDomainAndCode(domain, code)
                .map(link -> CachedLink.of(link, now))
                .orElseGet(() -> CachedLink.absent(now));
        cache.put(code, loaded);
        return loaded;
    }

    /**
     * Negative entries are held to {@code app.cache.negative-ttl} rather than to
     * the cache's own TTL. The two are configured separately because they defend
     * different things -- one bounds a missed invalidation, the other bounds how
     * long a code stays unknown after it has been issued -- and only one value can
     * be given to Redis.
     */
    private boolean isStillValid(CachedLink entry, Instant now) {
        return entry.present() || entry.cachedAt().plus(negativeTtl).isAfter(now);
    }
}
