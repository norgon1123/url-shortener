package com.example.urlshortener.api;

import com.example.urlshortener.domain.LinkStatus;
import java.time.Instant;

/**
 * One link, as returned by create, fetch, list and expiry update.
 *
 * <p>One representation for all four operations rather than a create-response
 * and a performance-response: they carry the same facts, and a second shape
 * would be one more thing the two branches could disagree about.
 *
 * <p>{@code clickCount} is the whole of "how a link has performed" (A8). No time
 * series, referrer, geography, device or unique-visitor data - intake put
 * analytics beyond the count out of scope, and the count is the only figure the
 * goal statement commits to.
 *
 * @param code       the short code; the stable identifier a client should key on
 * @param shortUrl   {@code app.base-url} + "/" + code. Returned alongside the
 *                   code rather than instead of it, so that a future
 *                   customer-owned domain changes this field's host without
 *                   breaking a client that stored the code (Q7).
 * @param longUrl    the target, unchanged since creation
 * @param status     see {@link LinkStatus}
 * @param createdAt  UTC instant, ISO-8601
 * @param expiresAt  UTC instant, ISO-8601; never null in this build
 * @param clickCount exact number of clicks served for this link: the durable
 *                   PostgreSQL total plus the un-flushed Redis delta, so it
 *                   reflects clicks made a moment ago rather than lagging by a
 *                   flush interval (A9). Clicks that 404 are not counted (A12).
 */
public record LinkResponse(
        String code,
        String shortUrl,
        String longUrl,
        LinkStatus status,
        Instant createdAt,
        Instant expiresAt,
        long clickCount) {}
