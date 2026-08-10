package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The limit on abuse reports (AC19), which is half the defence against the
 * takedown feature being weaponised.
 *
 * <p>A report blocks a link immediately with no human in the loop, so the only
 * things standing between this endpoint and a cheap way to kill a competitor's
 * links at scale are a signed-in session and this bucket. Both are worth pinning.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            Fixtures.RATE_LIMIT_ENABLED_KEY + "=true",
            Fixtures.ABUSE_REPORT_LIMIT_KEY + "=3"
        })
class AbuseReportRateLimitTest extends AbstractIntegrationTest {

    @BeforeEach
    void startFromEmptyBuckets() {
        resetSharedTierState();
    }

    /**
     * A reporter who exceeds the limit is refused with 429, and the links named in
     * the refused reports are still redirecting - a throttled report takes nothing
     * down.
     *
     * <p>Demonstrates: AC19, AC21.
     */
    @Test
    void reportsBeyondTheLimitAreRefusedAndTakeNothingDown() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A second customer can still report while the first is throttled: the bucket
     * is per reporter, so one abusive reporter does not disable abuse reporting
     * for everyone.
     *
     * <p>Demonstrates: AC19, AC21.
     */
    @Test
    void anotherReporterIsUnaffectedByOneReportersLimit() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
