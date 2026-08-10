package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Listing the caller's own links.
 *
 * <p>With no UI in this build, this is also the only way a customer who has lost
 * a code reaches their own link again, so it has to show them everything of
 * theirs - including what has expired, been deleted or been taken down - and
 * nothing of anybody else's (AC13).
 *
 * <p>The order is fixed by the contract (newest first, code ascending as the
 * tiebreak) because a blind test author needs a total order: two links created in
 * the same millisecond must not be able to swap places between runs.
 */
class ListLinksTest extends AbstractIntegrationTest {

    /**
     * The page contains the caller's links and no link belonging to anyone else,
     * whatever the other customer has created - the query is owner-scoped, so
     * another customer's link is not omitted from the list, it is invisible to it.
     *
     * <p>Demonstrates: AC13, AC7.
     */
    @Test
    void theListContainsOnlyTheCallersOwnLinks() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Links come back newest first, with the code ascending as the tiebreak, and
     * the same request twice gives the same order.
     *
     * <p>Demonstrates: AC7.
     */
    @Test
    void theListIsOrderedNewestFirstWithCodeAsTheTiebreak() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The caller's expired, deleted and blocked links appear in their list with
     * their statuses and retained counts: a customer can still see what happened
     * to a link they created.
     *
     * <p>Demonstrates: AC7, AC8, AC10, AC21.
     */
    @Test
    void theListIncludesTheCallersExpiredDeletedAndBlockedLinks() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Paging walks the whole collection: consecutive pages of a given size repeat
     * nothing and skip nothing, and the reported totals agree with what was
     * actually returned.
     *
     * <p>Demonstrates: AC7.
     */
    @Test
    void pagingWalksTheCollectionWithoutRepeatingOrSkipping() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A negative page, a size below one and a size above the maximum are each 400
     * {@code invalid_request} with the offending parameter named - not silently
     * clamped, which would make a client's paging quietly wrong.
     *
     * <p>Demonstrates: AC7.
     */
    @Test
    void anOutOfRangePageOrSizeIsRejected() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Listing without a session is 401 {@code unauthorized}.
     *
     * <p>Demonstrates: AC12.
     */
    @Test
    void listingWithoutASessionIsRefused() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
