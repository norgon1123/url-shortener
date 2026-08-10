package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Fetching one link and how it has performed (AC7), and the fact that this
 * endpoint answers nothing at all about anybody else's links (AC13, AC15).
 */
class LinkPerformanceTest extends AbstractIntegrationTest {

    /**
     * The owner of a link is shown the link and its click count: code, short URL,
     * target, status, creation and expiry times, and the exact number of clicks
     * served so far.
     *
     * <p>Demonstrates: AC7.
     */
    @Test
    void anOwnerSeesTheirLinkAndItsClickCount() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An owner still sees their own expired link, reported as expired, with the
     * count it accrued while it was live. It is their data and the row is kept;
     * only the redirect stops.
     *
     * <p>Demonstrates: AC7, AC10.
     */
    @Test
    void anOwnerSeesTheirOwnExpiredLinkWithItsRetainedCount() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An owner still sees their own deleted link, reported as deleted, with its
     * retained count.
     *
     * <p>Demonstrates: AC7, AC8.
     */
    @Test
    void anOwnerSeesTheirOwnDeletedLinkWithItsRetainedCount() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An owner still sees their own blocked link, reported as blocked, with its
     * retained count - so a takedown is visible to the person it affects rather
     * than making their link vanish silently.
     *
     * <p>Demonstrates: AC7, AC21.
     */
    @Test
    void anOwnerSeesTheirOwnBlockedLinkWithItsRetainedCount() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Another customer's link is 404 - never 403 - and the link is untouched by
     * the attempt: its count does not move and it keeps redirecting.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void anotherCustomersLinkIsNotFound() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Asking about another customer's live link and asking about a code that was
     * never issued produce the same status and the same body byte for byte, so
     * this endpoint cannot be used to discover which codes exist.
     *
     * <p>Demonstrates: AC13, AC15.
     */
    @Test
    void anotherCustomersLinkAndAnUnissuedCodeAnswerIdentically() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Asking about a link with no session is 401 {@code unauthorized} - the same
     * answer for a code that exists and one that does not, so an unauthenticated
     * caller learns nothing either.
     *
     * <p>Demonstrates: AC12, AC15.
     */
    @Test
    void fetchingALinkWithoutASessionIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
