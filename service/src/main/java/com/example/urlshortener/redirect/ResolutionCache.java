package com.example.urlshortener.redirect;

import com.example.urlshortener.config.CacheConfig;
import com.example.urlshortener.error.ApiException;
import com.example.urlshortener.error.ErrorCode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The resolution cache, and the only place that talks to it.
 *
 * <p><strong>Reads and ordinary invalidations never fail a request.</strong> The
 * click path is required to keep serving while this tier is unreachable, so a
 * failed lookup is a miss and the request falls through to PostgreSQL. A failed
 * invalidation on delete, expiry change or takedown is also swallowed: those
 * operations answer 204 or 200 or 202 and nothing else, and if this instance
 * cannot reach the tier then it is not serving entries from it either -- the
 * configured TTL is the floor under the published bound for the window where the
 * tier is up but a write to it was lost.
 *
 * <p><strong>Issuing a new code is the exception</strong>, and it is the one
 * place a 503 comes from. The cache is shared: this instance failing to reach it
 * says nothing about what other instances are still serving out of it, and if
 * some earlier request probed the code we are about to issue, they will keep
 * answering "no such code" for that code until the negative entry expires. A link
 * that does not redirect is worse than a create that was refused, and refusing it
 * is the shape AC20 asks for -- degradation spent on accepting links rather than
 * on serving clicks.
 */
@Component
public class ResolutionCache {

    private static final Logger log = LoggerFactory.getLogger(ResolutionCache.class);

    private final CacheManager cacheManager;

    public ResolutionCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** The cached entry, or empty on a miss or an unreachable tier. */
    public Optional<CachedLink> get(String code) {
        Cache cache = cache();
        if (cache == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(cache.get(code, CachedLink.class));
        } catch (RuntimeException unavailable) {
            log.debug("Resolution cache unreadable for {}: {}", code, unavailable.getMessage());
            return Optional.empty();
        }
    }

    /** Best-effort write; a failure costs a database read next time and nothing else. */
    public void put(String code, CachedLink value) {
        Cache cache = cache();
        if (cache == null) {
            return;
        }
        try {
            cache.put(code, value);
        } catch (RuntimeException unavailable) {
            log.debug("Resolution cache unwritable for {}: {}", code, unavailable.getMessage());
        }
    }

    /**
     * Clears any entry for a code that is about to be issued, and refuses the
     * operation if it cannot. See the class note for why this one is not
     * best-effort.
     *
     * @throws ApiException {@code service_unavailable} (503) if the tier cannot be
     *         reached
     */
    public void invalidateBeforeIssuing(String code) {
        Cache cache = cache();
        if (cache == null) {
            return;
        }
        try {
            cache.evict(code);
        } catch (RuntimeException unavailable) {
            log.warn("Refusing to issue {}: the resolution tier is unreachable: {}", code, unavailable.getMessage());
            throw ApiException.dependencyUnavailable(ErrorCode.SERVICE_UNAVAILABLE.defaultMessage());
        }
    }

    /**
     * Invalidates once the surrounding transaction has committed, so no reader can
     * re-cache the row in the state we are moving it out of.
     *
     * <p>The callback runs before the transactional method returns, which is what
     * makes the published takedown bound measurable from the delete or report
     * response rather than from some later moment.
     */
    public void invalidateAfterCommit(String code) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidateNow(code);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidateNow(code);
            }
        });
    }

    private void invalidateNow(String code) {
        Cache cache = cache();
        if (cache == null) {
            return;
        }
        try {
            cache.evict(code);
        } catch (RuntimeException unavailable) {
            log.warn("Could not invalidate {}; the entry will lapse with its TTL instead: {}",
                    code, unavailable.getMessage());
        }
    }

    private Cache cache() {
        return cacheManager.getCache(CacheConfig.LINKS_CACHE);
    }
}
