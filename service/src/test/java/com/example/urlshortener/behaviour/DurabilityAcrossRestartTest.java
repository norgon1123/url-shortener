package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractRestartIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * What survives the application going away and coming back.
 *
 * <p>"For the life of the link" (AC3) is a claim about durability, and every
 * other test in this suite would pass against an implementation that kept links
 * and counts in a map. This class is the one that would not: it boots the
 * application, acts, restarts the process against the same database and the same
 * Redis, and looks again.
 *
 * <p>The restart mechanism lives in {@link AbstractRestartIntegrationTest} and
 * nowhere else. Sessions are re-obtained after a restart rather than reused: the
 * signing key is ephemeral when none is configured, so surviving credentials are
 * not something this contract promises.
 */
class DurabilityAcrossRestartTest extends AbstractRestartIntegrationTest {

    /**
     * A link created before a restart still exists afterwards and still redirects
     * to the same target with the same code.
     *
     * <p>Demonstrates: AC2, AC1.
     */
    @Test
    void aLinkSurvivesARestartAndKeepsRedirecting() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Clicks counted before a restart are still counted after it: the total the
     * owner is shown is unchanged, so counting does not depend on anything that
     * dies with the process.
     *
     * <p>Demonstrates: AC3, AC7.
     */
    @Test
    void aClickCountSurvivesARestart() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Clicks made after a restart add to the retained total rather than starting
     * again from zero or from the durable figure alone.
     *
     * <p>Demonstrates: AC3, AC7.
     */
    @Test
    void clicksAfterARestartAddToTheRetainedTotal() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A link deleted before a restart is still not redirecting afterwards: a
     * takedown is durable, not a fact held in a cache that a restart forgets.
     *
     * <p>Demonstrates: AC8, AC9.
     */
    @Test
    void aDeletedLinkStaysDownAcrossARestart() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
