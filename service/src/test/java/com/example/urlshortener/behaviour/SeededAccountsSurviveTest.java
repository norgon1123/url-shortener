package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * The two hand-installed accounts are still there and still work (AC16).
 *
 * <p>This looks like a formality and is not. The change adds a uniqueness
 * constraint over the lower-cased address to a table those two rows already sit
 * in; if the constraint cannot be applied, or is applied by rewriting rows, they
 * are what breaks, and they are explicitly out of scope to remove or change.
 * Removing them is deferred work that depends on this landing first, so they have
 * to come through untouched.
 *
 * <p>The case-variant behaviour is here rather than in the sign-up class because
 * it is a claim about the seeded rows: a case-insensitive lookup over a table
 * that permitted case-variant duplicates could throw, which would be a 500 on the
 * untouched sign-in endpoint for an account that arrived by migration.
 */
class SeededAccountsSurviveTest extends AbstractIntegrationTest {

    /**
     * Both seeded accounts sign in with the credentials they were seeded with, and
     * get the tokens they got before.
     *
     * <p>Demonstrates: AC16.
     */
    @Test
    void bothSeededAccountsStillSignIn() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Each seeded address exists exactly once in storage after the change. The
     * uniqueness constraint this change adds is over the lower-cased address, and
     * a migration that duplicated or dropped a row while applying it would leave
     * sign-in either failing or ambiguous.
     *
     * <p>Demonstrates: AC16, AC6.
     */
    @Test
    void eachSeededAddressExistsExactlyOnceInStorage() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Signing in with a case variant of a seeded address still succeeds and
     * returns the same account. Case variants have always been one account; this
     * pins that the new constraint made that true of the data rather than merely
     * of the query.
     *
     * <p>Demonstrates: AC16, AC17.
     */
    @Test
    void signingInWithACaseVariantOfASeededAddressStillSucceeds() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A seeded customer is still an ordinary customer: they create a link, list
     * it, read it, change its expiry and delete it, all exactly as documented
     * before this change.
     *
     * <p>Demonstrates: AC16, AC17.
     */
    @Test
    void aSeededCustomerCanStillDoEverythingTheyCouldBefore() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A seeded customer's credentials are not affected by anybody signing up: the
     * stored form is the same one it was, and the account still signs in after
     * other accounts have been created.
     *
     * <p>Demonstrates: AC16, AC7.
     */
    @Test
    void aSeededCustomersStoredCredentialIsUntouchedBySignUps() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
