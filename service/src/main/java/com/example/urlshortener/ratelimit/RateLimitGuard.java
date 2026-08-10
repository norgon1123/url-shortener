package com.example.urlshortener.ratelimit;

import com.example.urlshortener.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Applies a bucket at the point the request is understood.
 *
 * <p>Deliberately not a servlet filter. Which bucket applies depends on things a
 * filter cannot see without re-deriving them: the write bucket is keyed by the
 * customer id, and the not-found bucket applies only to a request that turned out
 * not to resolve. Path-and-method matching in a filter would be a second, silent
 * copy of the routing table, and the failure mode of getting it wrong is a limit
 * that quietly applies to the wrong traffic.
 */
@Component
public class RateLimitGuard {

    private final RateLimiter limiter;

    public RateLimitGuard(RateLimiter limiter) {
        this.limiter = limiter;
    }

    /** Consumes a token keyed by client address, or refuses the request with 429. */
    public void requireByAddress(RateLimitBucket bucket, String clientAddress) {
        require(bucket, clientAddress);
    }

    /** Consumes a token keyed by the calling customer, or refuses the request with 429. */
    public void requireByCustomer(RateLimitBucket bucket, UUID customerId) {
        require(bucket, customerId.toString());
    }

    /**
     * The decision without the exception, for the click path, which builds all of
     * its own responses so that its cache headers are the same on every one.
     */
    public RateLimitDecision consume(RateLimitBucket bucket, String key) {
        return limiter.consume(bucket, key);
    }

    private void require(RateLimitBucket bucket, String key) {
        RateLimitDecision decision = limiter.consume(bucket, key);
        if (!decision.allowed()) {
            throw ApiException.rateLimited(decision.retryAfter());
        }
    }
}
