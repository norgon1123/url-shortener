package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Nobody owns an anonymous link, and the API says so by saying nothing (AC13).
 *
 * <p>Holding the link is the whole of the holder's relationship with the
 * service. Every management surface answers for an anonymous code exactly as it
 * answers for a code that was never issued: 404 {@code not_found}, with the same
 * body, for every caller including whoever created it. Never 403, which would
 * confirm the code exists; never 410, which would confirm it once did.
 *
 * <p>The callers exercised are deliberately three kinds - a seeded customer, a
 * customer created for this test, and no credential at all - because "no caller"
 * is the claim, and an implementation that special-cased one of them would pass a
 * narrower test.
 */
class AnonymousLinkInvisibilityTest extends AbstractIntegrationTest {

    /**
     * Reading an anonymous code through the management API answers 404 for a
     * signed-in caller, including one who has just created an anonymous link in
     * this very test.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void readingAnAnonymousCodeAnswersNotFoundForEverySignedInCaller() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * That 404 is byte-identical to the one an unissued code gets and the one
     * another customer's code gets: unknown, expired, deleted, blocked, somebody
     * else's and nobody's are one answer.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void theNotFoundForAnAnonymousCodeIsByteIdenticalToTheOneForAnUnissuedCode() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Reading an anonymous code with no credential is refused as unauthenticated,
     * exactly as reading any other code is. The management API's 401 comes before
     * any lookup, so it reveals nothing about the code either.
     *
     * <p>Demonstrates: AC13, AC17.
     */
    @Test
    void readingAnAnonymousCodeWithoutASessionIsRefusedAsUnauthenticated() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Changing an anonymous link's expiry answers 404 for every caller, and the
     * link is unchanged afterwards - it still redirects and still expires when it
     * said it would.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void changingAnAnonymousLinksExpiryAnswersNotFoundForEveryCaller() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Deleting an anonymous code answers 404 for every caller, and the link keeps
     * redirecting afterwards. Nobody can take one down through this route; the
     * abuse report is the only takedown for an unowned link.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void deletingAnAnonymousCodeAnswersNotFoundAndTakesNothingDown() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * An anonymous code appears in no customer's link list - not a seeded
     * customer's, not a newly created one's - on any page, and it is counted in no
     * customer's totals. A page that hid the row but counted it would leak that
     * something exists.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void anAnonymousCodeAppearsInNoCustomersLinkList() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Creating anonymous links does not change what an existing customer sees:
     * their list, their totals and their own links are exactly as they were. This
     * is the regression surface for rows with no owner arriving in a table every
     * owner-scoped query reads.
     *
     * <p>Demonstrates: AC13, AC17.
     */
    @Test
    void anonymousLinksDoNotDisturbAnExistingCustomersListing() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
