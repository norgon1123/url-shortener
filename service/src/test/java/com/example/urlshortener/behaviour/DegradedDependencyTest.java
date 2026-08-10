package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.TestInfrastructure;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What the service does while one of its dependencies is unavailable (AC20).
 *
 * <p>The stated preference is unambiguous: when the system cannot do both, a
 * click is served in preference to accepting a new link. That sentence only means
 * something if the failure can be produced, so the harness pauses the Redis
 * container - the tier that holds the click counters, the resolution cache and
 * the token buckets - and leaves PostgreSQL up. Pausing rather than stopping
 * keeps the port mapping, so the outage is reversible and the recovered half can
 * be observed too.
 *
 * <p>Every method here carries a timeout. The failure this class is looking for
 * is a click path that hangs on an unreachable dependency instead of degrading,
 * and a hang with no timeout is a suite that never finishes rather than a test
 * that fails.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class DegradedDependencyTest extends AbstractIntegrationTest {

    /**
     * Belt and braces: whatever a test does, the shared tier is running again
     * before the next class starts. A paused container leaks into every later
     * test in the JVM and the resulting failures point everywhere except here.
     */
    @AfterEach
    void restoreSharedTier() {
        TestInfrastructure.resumeCounterTier();
    }

    /**
     * With the counting and caching tier unreachable, a click on a live link still
     * answers 302 with the right target: the redirect is served from PostgreSQL,
     * and the count is what degrades, not the click.
     *
     * <p>Demonstrates: AC20, AC2.
     */
    @Test
    void clicksAreStillServedWhileTheCountingTierIsUnavailable() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Nothing on the click path answers with a server error while that tier is
     * down - not for a live code, not for an unknown one. A 5xx on the click path
     * is the outcome AC20 exists to forbid.
     *
     * <p>Demonstrates: AC20, AC15.
     */
    @Test
    void theClickPathNeverAnswersWithAServerError() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Requests are not refused as throttled because the limiter cannot reach its
     * store: the limiter fails open, since a dependency outage that turned every
     * click into a 429 would be a self-inflicted outage of the path being
     * protected.
     *
     * <p>Demonstrates: AC20, AC19.
     */
    @Test
    void throttlingFailsOpenRatherThanRefusingEveryClick() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * When the tier comes back, clicks are counted again and the figure reported
     * afterwards includes everything counted before the outage. Clicks served
     * during the outage may be lost - that is the degraded mode the design chose,
     * and losing the click instead would be the wrong trade - but nothing counted
     * either side of it is.
     *
     * <p>Demonstrates: AC20, AC3.
     */
    @Test
    void countingResumesWhenTheTierReturnsWithoutLosingEarlierClicks() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * If anything is refused while the tier is down it is link creation, with 503
     * {@code service_unavailable}, and never a click. This is the one place the
     * preference for serving clicks over accepting links is visible on the wire.
     *
     * <p>Demonstrates: AC20.
     */
    @Test
    void degradationIsSpentOnAcceptingLinksRatherThanOnServingClicks() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
