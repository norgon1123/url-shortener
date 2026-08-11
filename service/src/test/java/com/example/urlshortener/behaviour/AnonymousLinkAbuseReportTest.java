package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * The one takedown path an unowned link has (AC13, AC17).
 *
 * <p>The abuse-report endpoint is unchanged by this change, and that is a
 * deliberate constraint rather than an oversight: it answers 202 for any
 * well-formed code, including one nobody owns, and a report blocks that link
 * within the published bound. Carving anonymous codes out would reintroduce the
 * existence oracle the blanket 202 exists to prevent, and an unowned link is the
 * one most in need of a takedown path - nobody can delete it.
 *
 * <p>There is a residual gap worth stating where a reviewer will see it: a report
 * requires a session, so only a signed-in caller can get an anonymous link taken
 * down. The last behaviour here pins that rather than pretending otherwise.
 */
class AnonymousLinkAbuseReportTest extends AbstractIntegrationTest {

    /**
     * Reporting an anonymous code is accepted with 202, exactly as reporting an
     * owned code is - the same status and the same body, so the response says
     * nothing about whether the code exists or who holds it.
     *
     * <p>Demonstrates: AC13, AC17.
     */
    @Test
    void reportingAnAnonymousCodeIsAcceptedLikeAnyOtherCode() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A reported anonymous link stops redirecting within the published takedown
     * bound, measured from the response rather than assumed. The bound is the
     * figure the business is held to, and it applies to a link with no owner as
     * much as to one with.
     *
     * <p>Demonstrates: AC13, AC17.
     */
    @Test
    void aReportedAnonymousLinkStopsRedirectingWithinThePublishedBound() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Once blocked, the code answers the same 404 as an unissued one on the click
     * path: blocked is not a distinguishable state to anyone holding the link.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void aBlockedAnonymousCodeIsIndistinguishableFromOneNeverIssued() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A report still requires a session: an unauthenticated caller reporting an
     * anonymous code is refused as unauthenticated, unchanged from today. This is
     * the residual gap - the one link with no owner to complain about it can only
     * be reported by somebody with an account - and it is pinned so that it is a
     * decision on the record rather than a surprise.
     *
     * <p>Demonstrates: AC13, AC17.
     */
    @Test
    void reportingAnAnonymousCodeStillRequiresASession() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
