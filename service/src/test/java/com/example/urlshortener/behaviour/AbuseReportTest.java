package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Reporting a link as abusive, and the takedown that follows (AC21, AC9).
 *
 * <p>A report blocks the link immediately: there is no moderation console in this
 * build, so there is nobody for a queued report to wait for. The endpoint is
 * authenticated and rate-limited per reporter, and that pair is the entire
 * defence against the feature being a cheap way to kill a competitor's links -
 * the limit itself is exercised in its own class, because it needs a bucket small
 * enough to empty.
 *
 * <p>It answers 202 for any well-formed code whether or not it resolves. A 404
 * here would be the existence oracle every other endpoint is careful not to be,
 * and this is the one endpoint that takes a code from an untrusted caller.
 */
class AbuseReportTest extends AbstractIntegrationTest {

    /**
     * A reported link stops redirecting: the click path answers the single 404 and
     * the link is blocked.
     *
     * <p>Demonstrates: AC21.
     */
    @Test
    void aReportedLinkStopsRedirecting() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * It stops within the published bound, measured from the report response - the
     * same number a delete is held to, because a fraud takedown is the case the
     * bound exists for.
     *
     * <p>Demonstrates: AC9, AC21.
     */
    @Test
    void aReportTakesEffectWithinThePublishedBound() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A report against a code that was never issued is accepted with the same 202
     * and the same empty body as one against a real link, so the endpoint cannot
     * be used to ask which codes exist.
     *
     * <p>Demonstrates: AC15, AC21.
     */
    @Test
    void aReportAgainstAnUnissuedCodeIsAcceptedLikeAnyOther() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A report against another customer's link is accepted and takes it down:
     * anyone signed in may report any link they can name, which is the deliberate
     * trade this design makes and the reason the endpoint is limited per reporter.
     *
     * <p>Demonstrates: AC21, AC9.
     */
    @Test
    void aReportAgainstAnotherCustomersLinkTakesItDown() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A report with no body at all is accepted: the reason is optional, and the
     * reporter and the time are what the record needs.
     *
     * <p>Demonstrates: AC21.
     */
    @Test
    void aReportWithNoBodyIsAccepted() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A report body carrying a property the schema does not define, or a reason
     * longer than the maximum, is 400 - and the link is not blocked by a request
     * that was refused.
     *
     * <p>Demonstrates: AC21.
     */
    @Test
    void aMalformedReportBodyIsRejectedAndTakesNothingDown() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Reporting without a session is 401 and the link keeps redirecting: an
     * unauthenticated caller cannot take anything down.
     *
     * <p>Demonstrates: AC12, AC21.
     */
    @Test
    void reportingWithoutASessionIsRefusedAndTakesNothingDown() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The owner of a reported link still sees it, reported as blocked, with its
     * retained click count - the takedown is visible to them rather than silent.
     *
     * <p>Demonstrates: AC7, AC21.
     */
    @Test
    void theOwnerOfAReportedLinkStillSeesItAndItsCount() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
