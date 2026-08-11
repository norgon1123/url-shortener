package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.SignInResponse;
import com.example.urlshortener.auth.PasswordHasher;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.util.Locale;
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
        String password = "a-correct-horse-battery-staple";

        String stored = passwordHasher.hash(password);

        assertAll(
                () -> assertNotEquals(password, stored),
                () -> assertFalse(stored.contains(password), "the password itself is not in the stored form"),
                () -> assertFalse(
                        stored.toLowerCase(Locale.ROOT).contains(password.toLowerCase(Locale.ROOT)),
                        "nor is it there in a different case"),
                // A memory-hard KDF records what it did; a bare digest cannot be
                // told from another bare digest and cannot carry a salt.
                () -> assertTrue(stored.startsWith("$"), "the stored form names its algorithm: " + stored),
                () -> assertTrue(
                        stored.chars().filter(c -> c == '$').count() >= 3,
                        "and carries its parameters and salt: " + stored),
                () -> assertFalse(
                        stored.matches("^[0-9a-fA-F]{32,128}$"),
                        "a bare hex digest is not a defensible stored credential: " + stored));
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
        String password = "two-customers-chose-the-same-one";

        String first = passwordHasher.hash(password);
        String second = passwordHasher.hash(password);

        assertAll(
                () -> assertNotEquals(first, second,
                        "equal passwords must not be visibly equal in the database"),
                () -> assertTrue(passwordHasher.matches(password, first)),
                () -> assertTrue(passwordHasher.matches(password, second),
                        "both stored forms still recognise the password they were made from"));
    }

    /**
     * A stored form still recognises the password it was made from and rejects
     * every other candidate, including one differing by a single character.
     *
     * <p>Demonstrates: AC17.
     */
    @Test
    void aStoredFormRecognisesOnlyTheOriginalPassword() {
        String password = "the-one-that-was-chosen";
        String stored = passwordHasher.hash(password);

        assertAll(
                () -> assertTrue(passwordHasher.matches(password, stored)),
                () -> assertFalse(passwordHasher.matches(password + "x", stored),
                        "one extra character is a different password"),
                () -> assertFalse(passwordHasher.matches("the-one-that-was-chose", stored)),
                () -> assertFalse(passwordHasher.matches("The-One-That-Was-Chosen", stored),
                        "case matters"),
                () -> assertFalse(passwordHasher.matches("", stored)),
                () -> assertFalse(passwordHasher.matches(stored, stored),
                        "the stored form is not itself a password that opens the account"));
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
        HttpResponse<String> alicesSignIn =
                api.signIn(Fixtures.ALICE.email(), Fixtures.ALICE.plaintext());
        HttpResponse<String> bobsSignIn = api.signIn(Fixtures.BOB.email(), Fixtures.BOB.plaintext());
        HttpResponse<String> withTheOtherAccountsPassword =
                api.signIn(Fixtures.ALICE.email(), Fixtures.BOB.plaintext());

        assertEquals(200, alicesSignIn.statusCode(), alicesSignIn.body());
        assertEquals(200, bobsSignIn.statusCode(), bobsSignIn.body());
        SignInResponse alicesSession = ApiClient.asSession(alicesSignIn);
        SignInResponse bobsSession = ApiClient.asSession(bobsSignIn);
        assertAll(
                () -> assertEquals(Fixtures.ALICE.id(), alicesSession.customerId(),
                        "the migration stored the form this component produces"),
                () -> assertEquals(Fixtures.BOB.id(), bobsSession.customerId()),
                () -> assertEquals(401, withTheOtherAccountsPassword.statusCode(),
                        "and the check really runs, rather than accepting anything"));
    }
}
