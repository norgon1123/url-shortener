package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Throttling the click surface (AC19) without throttling a link that has gone
 * viral (AC22).
 *
 * <p>Two buckets, both keyed by client IP, and the whole design turns on them
 * being separate: an enumeration sweep is a long run of 404s while a hot link is
 * a long run of 302s, so the not-found bucket is set an order of magnitude
 * tighter than the click bucket. This class runs with the not-found bucket small
 * enough to empty in a few requests and the click bucket left generous, which is
 * the production shape in miniature.
 *
 * <p>Every test starts from an empty shared tier, because one Redis is shared by
 * the whole JVM and a bucket another test drained is still drained a second
 * later.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            Fixtures.RATE_LIMIT_ENABLED_KEY + "=true",
            Fixtures.NOT_FOUND_LIMIT_KEY + "=5",
            Fixtures.CLICK_LIMIT_KEY + "=1000"
        })
class ClickRateLimitTest extends AbstractIntegrationTest {

    @BeforeEach
    void startFromEmptyBuckets() {
        resetSharedTierState();
    }

    /**
     * A sweep of codes that do not resolve is refused once the not-found bucket is
     * empty: the first few answer 404 and the rest answer 429, so guessing costs
     * the attacker more than it costs us.
     *
     * <p>Demonstrates: AC19, AC16.
     */
    @Test
    void aSweepOfUnissuedCodesIsThrottled() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A throttled response carries {@code Retry-After} in whole seconds, at least
     * one - never zero, which would tell a client to come straight back and turn
     * the limiter into an amplifier.
     *
     * <p>Demonstrates: AC19.
     */
    @Test
    void aThrottledClickCarriesRetryAfterInWholeSeconds() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * With the not-found bucket exhausted by a sweep, a real link on the same
     * address keeps being served with 302s and keeps being counted. Throttling the
     * scraper does not throttle the audience.
     *
     * <p>Demonstrates: AC22, AC19, AC3.
     */
    @Test
    void aRealLinkKeepsBeingServedWhileTheEnumerationBucketIsEmpty() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A request refused with 429 is not counted as a click on anything: a
     * throttled sweep cannot move any link's reported figure.
     *
     * <p>Demonstrates: AC3, AC19.
     */
    @Test
    void aThrottledRequestIsNotCountedAsAClick() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A throttled click answers 429 with the {@code rate_limited} body and never a
     * 5xx: the click path is the one that must stay up, so refusing a request is
     * not the same as failing.
     *
     * <p>Demonstrates: AC19, AC20.
     */
    @Test
    void throttlingIsARefusalAndNeverAServerError() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
