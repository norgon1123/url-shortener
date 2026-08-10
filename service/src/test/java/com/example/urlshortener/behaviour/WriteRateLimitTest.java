package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Throttling bulk junk-link creation (AC19), keyed by customer.
 *
 * <p>The write bucket is the one keyed by customer id rather than by address,
 * which makes it the only place this suite can show the second half of AC19 -
 * that while one source is being throttled, other customers keep being served.
 * Two seeded accounts and one limit are enough; two client addresses are not
 * something a test running from loopback can produce.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            Fixtures.RATE_LIMIT_ENABLED_KEY + "=true",
            Fixtures.WRITE_LIMIT_KEY + "=5"
        })
class WriteRateLimitTest extends AbstractIntegrationTest {

    @BeforeEach
    void startFromEmptyBuckets() {
        resetSharedTierState();
    }

    /**
     * A customer creating links far faster than the limit allows starts being
     * refused with 429 once their bucket is empty, and the links that were refused
     * do not exist afterwards - the storage burn is what the limit is for.
     *
     * <p>Demonstrates: AC19.
     */
    @Test
    void bulkLinkCreationByOneCustomerIsThrottled() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * While one customer is being throttled, the other customer's creates, fetches
     * and deletes all succeed: the limit is per customer, so one abusive account
     * does not degrade anybody else.
     *
     * <p>Demonstrates: AC19, AC13.
     */
    @Test
    void anotherCustomerKeepsBeingServedWhileOneIsThrottled() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A throttled write carries {@code Retry-After} in whole seconds, at least
     * one, and the {@code rate_limited} body.
     *
     * <p>Demonstrates: AC19.
     */
    @Test
    void aThrottledWriteCarriesRetryAfterInWholeSeconds() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Clicks on existing links keep being served and counted while a customer's
     * write bucket is empty: the click path does not share a bucket with the write
     * path, and serving a click is preferred to accepting a link.
     *
     * <p>Demonstrates: AC19, AC20, AC22.
     */
    @Test
    void clicksAreUnaffectedWhileACustomersWriteBucketIsEmpty() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
