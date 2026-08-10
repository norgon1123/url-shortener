package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * When a link stops working on its own (AC10) and how its expiry is changed
 * (AC11).
 *
 * <p>Expiry is the whole of "editing a link": the target URL is immutable for the
 * life of the code, so a short link that has been shared cannot be repointed at
 * something else afterwards. The tests that need an expiry to pass use the
 * harness's short-expiry helper rather than reaching into the database, which is
 * not part of the frozen contract; three seconds is the cost, paid by four tests.
 */
class LinkExpiryTest extends AbstractIntegrationTest {

    /**
     * A link created without an expiry gets one about a month out - the configured
     * default rather than a number welded into the code.
     *
     * <p>Demonstrates: AC10, AC11.
     */
    @Test
    void aLinkCreatedWithoutAnExpiryGetsTheDefaultOne() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Once its expiry has passed, a link no longer redirects: the click path
     * answers the single 404, and the owner sees it as expired.
     *
     * <p>Demonstrates: AC10.
     */
    @Test
    void anExpiredLinkNoLongerRedirects() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An individual link's expiry can be set to a new future instant, the response
     * carries the new value, and a later fetch agrees - the change is per link and
     * needs no code change.
     *
     * <p>Demonstrates: AC11.
     */
    @Test
    void theExpiryOfAnIndividualLinkCanBeChanged() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Bringing the expiry forward stops the link redirecting once the new time
     * passes, without waiting out the old one and without waiting for a cache to
     * expire on its own.
     *
     * <p>Demonstrates: AC10, AC11, AC9.
     */
    @Test
    void bringingTheExpiryForwardStopsTheRedirectWhenItPasses() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Pushing a soon-to-expire link's expiry further out keeps it redirecting past
     * the original time.
     *
     * <p>Demonstrates: AC11, AC10.
     */
    @Test
    void pushingTheExpiryOutKeepsALinkRedirecting() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An expiry in the past is 400 and changes nothing: the link keeps redirecting
     * and keeps its old expiry. A past timestamp is not a takedown - delete is,
     * and delete invalidates the caches, which a backdated expiry would not.
     *
     * <p>Demonstrates: AC11, AC9.
     */
    @Test
    void anExpiryInThePastIsRejectedAndChangesNothing() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A patch with no expiry in it at all is 400 with the field named, rather than
     * a successful no-op.
     *
     * <p>Demonstrates: AC11.
     */
    @Test
    void aPatchWithoutAnExpiryIsRejected() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A patch carrying the target URL - or any other property - is 400, and the
     * link's target is unchanged afterwards. Immutability is mechanical: the field
     * fails loudly instead of being silently ignored, which is what stops a shared
     * link being repointed at something else after the fact.
     *
     * <p>Demonstrates: AC11, AC21.
     */
    @Test
    void aPatchCarryingTheTargetUrlIsRejectedRatherThanIgnored() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Patching another customer's link is 404 - identical to patching a code that
     * was never issued - and that customer's link is untouched: same expiry, still
     * redirecting.
     *
     * <p>Demonstrates: AC13, AC15.
     */
    @Test
    void patchingAnotherCustomersLinkIsNotFoundAndChangesNothing() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Patching one's own deleted link is 409 {@code link_not_modifiable}: its
     * expiry no longer means anything, and the answer is a conflict rather than a
     * 404 because the caller does own it.
     *
     * <p>Demonstrates: AC8, AC11.
     */
    @Test
    void patchingOnesOwnDeletedLinkIsRefusedAsNotModifiable() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Patching one's own blocked link is 409 and the link stays down: an abuse
     * takedown must not be reversible by its owner pushing the expiry out.
     *
     * <p>Demonstrates: AC21, AC9.
     */
    @Test
    void patchingOnesOwnBlockedLinkCannotUndoTheTakedown() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Patching without a session is 401 {@code unauthorized}, whether or not the
     * code exists.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void patchingWithoutASessionIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
