package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * One customer against another's links (AC13, AC15).
 *
 * <p>The individual endpoints each carry their own not-yours case. This class
 * exists because the criterion is stronger than the sum of those: across the
 * whole owner-scoped surface, the answer for another customer's link must be the
 * same answer as for a code that was never issued - one status, one body - and
 * the attempt must leave that link exactly as it was.
 *
 * <p>Checking them together is what catches the endpoint that was added later and
 * answered 403, or 404 with a slightly different body, and so became the oracle
 * every other endpoint was careful not to be.
 */
class TenantIsolationTest extends AbstractIntegrationTest {

    /**
     * Fetch, list, patch, delete and report, aimed at another customer's link and
     * then at a code that was never issued, produce the same answers as each
     * other: no endpoint distinguishes "somebody else's" from "does not exist".
     *
     * <p>Demonstrates: AC13, AC15.
     */
    @Test
    void everyOwnerScopedEndpointAnswersTheSameForAnotherCustomersLinkAsForAnUnissuedCode() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * After all of those attempts the other customer's link is untouched: same
     * target, same expiry, same status, same click count, and it still redirects.
     * Interference is not only refused, it leaves no trace.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void anotherCustomersLinkIsUnchangedByEveryAttemptOnIt() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Ownership is decided by the session and nothing else: the same request with
     * the owner's session succeeds where the other customer's was refused, so the
     * refusals are isolation rather than a broken code.
     *
     * <p>Demonstrates: AC13, AC12.
     */
    @Test
    void theOwnersOwnSessionSucceedsWhereTheOtherCustomersWasRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A customer's list, and the totals reported with it, count only their own
     * links however many the other customer creates.
     *
     * <p>Demonstrates: AC13, AC7.
     */
    @Test
    void aCustomersOwnTotalsAreUnaffectedByAnotherCustomersLinks() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
