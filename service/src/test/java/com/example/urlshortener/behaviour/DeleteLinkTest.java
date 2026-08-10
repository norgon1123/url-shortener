package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Taking a link down (AC8), inside the bound the business is held to (AC9).
 *
 * <p>The bound is sixty seconds measured from the delete response, and it is
 * published in the API document, so it is quoted from the harness rather than
 * guessed at here. It is measured rather than assumed: the harness reports how
 * long the link kept redirecting after the response, and the test says what that
 * number has to be under.
 */
class DeleteLinkTest extends AbstractIntegrationTest {

    /**
     * After its owner deletes it, clicking the link no longer redirects to the
     * original address.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void aDeletedLinkNoLongerRedirects() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The link stops redirecting within the published bound, measured from the
     * delete response - not merely eventually, and not only after a cache has aged
     * out on its own.
     *
     * <p>Demonstrates: AC9, AC8.
     */
    @Test
    void aDeleteTakesEffectWithinThePublishedBound() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A link that was clicked before the delete stops redirecting too: a
     * previously resolved, cached link is invalidated rather than left to serve
     * from the cache.
     *
     * <p>Demonstrates: AC8, AC9.
     */
    @Test
    void aLinkThatWasAlreadyBeingClickedStopsToo() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Deleting the same link again is another 204: the operation is idempotent, so
     * a retried delete is not an error and does not disclose that the first one
     * worked.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void deletingAnAlreadyDeletedLinkIsAcceptedAgain() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The delete response carries no body at all. There is nothing to say, and a
     * body describing what was deleted would be a way to confirm what existed.
     *
     * <p>Demonstrates: AC8, AC13.
     */
    @Test
    void aDeleteReturnsNoBody() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * On the click path a deleted code answers exactly as a code that was never
     * issued: same status, same body, same headers, and it is not counted.
     *
     * <p>Demonstrates: AC8, AC15, AC3.
     */
    @Test
    void aDeletedCodeAnswersExactlyLikeAnUnissuedOne() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Deleting another customer's link is 404 - identical to deleting a code that
     * was never issued - and their link keeps redirecting afterwards. This is the
     * interference half of tenant isolation: not only can it not be seen, it
     * cannot be touched.
     *
     * <p>Demonstrates: AC13, AC15.
     */
    @Test
    void deletingAnotherCustomersLinkIsNotFoundAndLeavesItWorking() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Deleting without a session is 401 {@code unauthorized} and the link is
     * untouched.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void deletingWithoutASessionIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
