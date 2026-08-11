package com.example.urlshortener.ratelimit;

import java.time.Duration;

/**
 * The outcome of one token-bucket consumption.
 *
 * @param allowed   whether the request proceeds
 * @param remaining tokens left in the bucket after this call
 * @param retryAfter how long until a token is available; sent as
 *                   {@code Retry-After} on a 429. Always a whole number of
 *                   seconds and at least 1, because {@code Retry-After: 0} tells
 *                   a client to come straight back and makes the limiter a
 *                   busy-loop amplifier.
 */
public record RateLimitDecision(boolean allowed, long remaining, Duration retryAfter) {

    /**
     * The decision returned when limiting is disabled or the limiter itself is
     * unavailable.
     *
     * <p>The limiter fails open. It is a defence against abuse, not a
     * correctness control, and a Redis outage that turned every click into a 429
     * would be a self-inflicted outage of exactly the path AC20 says to protect.
     */
    public static final RateLimitDecision ALLOWED = new RateLimitDecision(true, Long.MAX_VALUE, Duration.ZERO);
}
