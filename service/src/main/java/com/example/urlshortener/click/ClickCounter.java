package com.example.urlshortener.click;

import java.util.UUID;

/**
 * Counts clicks (AC3/A9).
 *
 * <p>Redis takes the increment on the hot path and a scheduled job drains the
 * deltas into PostgreSQL in batches. A synchronous row update per click does not
 * hold at ten clicks per creation against 100M creations a day, and it would put
 * PostgreSQL in the path of a redirect, which AC20 forbids.
 *
 * <p>"Exact" is read as A3 and Q5 read it: measured, never sampled or estimated,
 * and never lost to a browser or an intermediary caching the redirect. It is not
 * read as "survives total loss of the counting tier" - the click is served
 * whatever happens, because AC20 says to prefer serving it.
 *
 * <p>Clicks that 404 are not counted (A12): a dead link has no redirect to
 * attribute, and counting them would let anyone inflate another customer's
 * reported figures by hammering a deleted code.
 */
public interface ClickCounter {

    /**
     * Records one served redirect.
     *
     * <p>Best-effort and non-blocking with respect to the response: it must never
     * throw and must never delay the 302. If Redis is unavailable the click is
     * still served (AC20) and the increment is lost - that is the degraded mode,
     * and it is logged rather than silently swallowed.
     */
    void record(UUID linkId);

    /**
     * @return clicks recorded but not yet flushed to PostgreSQL, or 0 if the
     *         counter is unavailable. Added to the durable total when a link's
     *         performance is reported, so the number a customer reads reflects a
     *         click made a second ago rather than lagging by a flush interval.
     */
    long pendingDelta(UUID linkId);
}
