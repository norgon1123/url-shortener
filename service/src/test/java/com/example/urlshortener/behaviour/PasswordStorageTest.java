package com.example.urlshortener.behaviour;

import com.example.urlshortener.auth.PasswordHasher;
import com.example.urlshortener.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * How a credential is stored (AC17).
 *
 * <p>AC17 is a claim about what someone holding a full copy of the database can
 * do, and no HTTP response can show it: sign-in works whether the stored form is
 * a memory-hard hash or the password itself. So this is the one family of
 * behaviour in the suite that is examined at the component that owns the
 * decision, through the frozen {@code PasswordHasher} port. The schema is not
 * part of the frozen contract, so the test reaches the hashing, not the table.
 */
class PasswordStorageTest extends AbstractIntegrationTest {

    /** The component that decides what a stored credential looks like. */
    @Autowired
    protected PasswordHasher passwordHasher;

    /**
     * The stored form neither is nor contains the original password, and is not a
     * bare digest of it: it carries an algorithm identifier and parameters, which
     * is what a reader of a stolen database sees instead of a password.
     *
     * <p>Demonstrates: AC17.
     */
    @Test
    void theStoredFormIsNotThePasswordAndDoesNotContainIt() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Hashing the same password twice gives two different stored forms, so equal
     * passwords are not visibly equal in the database and one precomputed table
     * does not open every account at once.
     *
     * <p>Demonstrates: AC17.
     */
    @Test
    void theSamePasswordIsStoredDifferentlyEachTime() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * A stored form still recognises the password it was made from and rejects
     * every other candidate, including one differing by a single character.
     *
     * <p>Demonstrates: AC17.
     */
    @Test
    void aStoredFormRecognisesOnlyTheOriginalPassword() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The seeded accounts sign in successfully, which means the migration stored
     * the same form this component produces - the two halves of the fixture agree,
     * and a credential check that never runs cannot be the reason a later test
     * passes.
     *
     * <p>Demonstrates: AC17, AC12.
     */
    @Test
    void theSeededAccountsSignInAgainstTheirStoredForm() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
