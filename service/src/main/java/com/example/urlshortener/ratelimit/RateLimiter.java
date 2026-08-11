package com.example.urlshortener.ratelimit;

/**
 * Token buckets in Redis, so a limit is a limit across every instance rather
 * than per instance times the replica count.
 *
 * <p>Hand-rolled as a Lua script over the connection the Redis starter already
 * provides: no extra dependency, no second supply-chain approval on a frozen
 * pom, and - because the script touches one key per call - it keeps working
 * unchanged if the shared Redis turns out to be a Cluster. U1 is unresolved, so
 * single-key operations are the cheap insurance.
 */
public interface RateLimiter {

    /**
     * Consumes one token.
     *
     * @param bucket which limit applies
     * @param key    the identity the limit is applied to: client IP for
     *               {@link RateLimitBucket#CLICK}, {@link RateLimitBucket#NOT_FOUND}
     *               and {@link RateLimitBucket#SIGN_IN}, customer id for
     *               {@link RateLimitBucket#WRITE} and
     *               {@link RateLimitBucket#ABUSE_REPORT}
     * @return the decision; never null, and never an exception. An unreachable
     *         Redis returns {@link RateLimitDecision#ALLOWED}
     */
    RateLimitDecision consume(RateLimitBucket bucket, String key);
}
