package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * An account somebody made for themselves is as isolated as one that arrived by
 * migration (AC8).
 *
 * <p>The existing suite proves isolation between the two seeded customers. That
 * is not the same claim: seeded ids are fixed, consecutive and present at every
 * boot, and an implementation that keyed a shortcut off them - or that gave a
 * newly created account a null, default or shared tenant - would pass every
 * existing test and fail here. Both directions are exercised, because "cannot
 * see" is a claim about a pair.
 */
class SignUpIsolationTest extends AbstractIntegrationTest {

    /**
     * A newly created account's link list is empty, and stays empty when other
     * customers create links. A new customer starts owning nothing, however many
     * links exist.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void aNewlyCreatedAccountOwnsNothing() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A self-signed-up customer naming another customer's code gets 404
     * {@code not_found}, byte-identical to the answer for a code that was never
     * issued - never 403, which would confirm the code exists.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void aSignedUpCustomerCannotReadAnotherCustomersLink() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The same code answers 404 to a change of expiry from that customer, and the
     * link's expiry is unchanged afterwards.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void aSignedUpCustomerCannotChangeAnotherCustomersLink() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The same code answers 404 to a delete from that customer, and the link keeps
     * redirecting afterwards. A 404 that deleted the row anyway would be the worst
     * of both.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void aSignedUpCustomerCannotDeleteAnotherCustomersLink() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A self-signed-up customer's list contains their own links and nobody else's,
     * across every page - the totals as well as the contents, since a page that
     * hid other customers' rows while counting them would leak how many exist.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void aSignedUpCustomersListContainsOnlyTheirOwnLinks() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The other direction: a link created by a self-signed-up customer is invisible
     * to a seeded customer, who gets the same 404 for it and never sees it in a
     * list.
     *
     * <p>Demonstrates: AC8, AC17.
     */
    @Test
    void aSignedUpCustomersLinkIsInvisibleToAnExistingCustomer() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
