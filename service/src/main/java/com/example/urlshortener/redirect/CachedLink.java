package com.example.urlshortener.redirect;

import com.example.urlshortener.domain.LinkEntity;
import com.example.urlshortener.domain.LinkStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * What the resolution cache holds for one code: the few facts the click path
 * needs, and nothing else.
 *
 * <p>A code that resolves to nothing has a value here rather than being absent.
 * Negative caching is what keeps an enumeration sweep off PostgreSQL, and giving
 * "no such code" a representable form lets the cache be typed to a single class
 * instead of carrying null markers and polymorphic type information.
 *
 * <p>Expiry is <em>not</em> baked in. The entry records when the link expires and
 * every reader compares that against the clock, so an entry cached while a link
 * was live stops redirecting the moment the expiry passes, without anything
 * having to sweep or the entry having to be evicted.
 *
 * @param present    whether the code resolves to a link at all
 * @param id         link id, for attributing clicks; null when absent
 * @param longUrl    redirect target; null when absent
 * @param status     the stored status, never {@link LinkStatus#EXPIRED}
 * @param expiresAt  when the link stops redirecting; null when absent
 * @param cachedAt   when this entry was built, so a negative entry can be held to
 *                   {@code app.cache.negative-ttl} independently of the Redis TTL
 *                   that bounds the positive ones
 */
public record CachedLink(
        boolean present,
        UUID id,
        String longUrl,
        LinkStatus status,
        Instant expiresAt,
        Instant cachedAt) {

    public static CachedLink of(LinkEntity link, Instant cachedAt) {
        return new CachedLink(
                true, link.getId(), link.getLongUrl(), link.getStatus(), link.getExpiresAt(), cachedAt);
    }

    /** The "this code resolves to nothing" form. */
    public static CachedLink absent(Instant cachedAt) {
        return new CachedLink(false, null, null, null, null, cachedAt);
    }

    /** Whether a click on this code redirects at {@code now}. */
    public boolean redirectsAt(Instant now) {
        return present && status == LinkStatus.ACTIVE && expiresAt.isAfter(now);
    }
}
