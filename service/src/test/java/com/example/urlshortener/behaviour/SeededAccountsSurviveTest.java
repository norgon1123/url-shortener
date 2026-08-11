package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkPage;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.domain.CustomerEntity;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
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
     * Starts from full buckets. Every behaviour here signs in as a seeded customer,
     * and one of them creates accounts; both are metered from the single client
     * address this suite runs from, in the Redis every context shares. A bucket an
     * earlier class drained would answer 429 and the failure would read as a
     * seeded account that no longer works. There are no clicks before this runs.
     */
    @BeforeEach
    void startFromFullBuckets() {
        resetSharedTierState();
    }

    /**
     * Both seeded accounts sign in with the credentials they were seeded with, and
     * get the tokens they got before.
     *
     * <p>Demonstrates: AC16.
     */
    @Test
    void bothSeededAccountsStillSignIn() {
        HttpResponse<String> alicesSignIn =
                api.signIn(Fixtures.ALICE.email(), Fixtures.ALICE.plaintext());
        HttpResponse<String> bobsSignIn = api.signIn(Fixtures.BOB.email(), Fixtures.BOB.plaintext());
        HttpResponse<String> withTheWrongPassword =
                api.signIn(Fixtures.ALICE.email(), Fixtures.BOB.plaintext());

        assertEquals(200, alicesSignIn.statusCode(), alicesSignIn.body());
        assertEquals(200, bobsSignIn.statusCode(), bobsSignIn.body());
        assertAll(
                () -> assertEquals(Fixtures.ALICE.id(), ApiClient.asSession(alicesSignIn).customerId(),
                        "the same identity these accounts always had"),
                () -> assertEquals(Fixtures.BOB.id(), ApiClient.asSession(bobsSignIn).customerId()),
                () -> assertEquals("Bearer", ApiClient.asSession(alicesSignIn).tokenType()),
                () -> assertTrue(ApiClient.asSession(alicesSignIn).expiresAt().isAfter(Instant.now())),
                () -> assertEquals(401, withTheWrongPassword.statusCode(),
                        "and the credential check really runs: " + withTheWrongPassword.body()));
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
        List<CustomerEntity> aliceRows = storedAccountsNamed(Fixtures.ALICE.email());
        List<CustomerEntity> bobRows = storedAccountsNamed(Fixtures.BOB.email());

        assertAll(
                () -> assertEquals(1, aliceRows.size(),
                        "neither duplicated nor dropped by the new constraint: " + aliceRows.size()
                                + " rows for " + Fixtures.ALICE.email()),
                () -> assertEquals(1, bobRows.size(),
                        bobRows.size() + " rows for " + Fixtures.BOB.email()),
                () -> assertEquals(Fixtures.ALICE.id(), aliceRows.get(0).getId(),
                        "the row is the seeded one, not a replacement written by a migration"),
                () -> assertEquals(Fixtures.BOB.id(), bobRows.get(0).getId()),
                () -> assertEquals(Fixtures.ALICE.email(), aliceRows.get(0).getEmail(),
                        "and the address was not rewritten to apply the constraint"),
                () -> assertEquals(Fixtures.BOB.email(), bobRows.get(0).getEmail()));
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
        HttpResponse<String> shouted =
                api.signIn(Fixtures.ALICE.email().toUpperCase(Locale.ROOT), Fixtures.ALICE.plaintext());
        HttpResponse<String> mixedCase =
                api.signIn(capitalise(Fixtures.BOB.email()), Fixtures.BOB.plaintext());

        assertAll(
                () -> assertEquals(200, shouted.statusCode(),
                        "a case variant is the same account: " + shouted.body()),
                () -> assertEquals(Fixtures.ALICE.id(), ApiClient.asSession(shouted).customerId()),
                () -> assertEquals(200, mixedCase.statusCode(), mixedCase.body()),
                () -> assertEquals(Fixtures.BOB.id(), ApiClient.asSession(mixedCase).customerId()),
                () -> assertNotEquals(500, shouted.statusCode(),
                        "two case-variant rows would make this lookup non-unique and 500 the "
                                + "untouched sign-in endpoint"));
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
        String alice = alice();

        HttpResponse<String> created = api.createLink(alice, Fixtures.TARGET_URL);
        LinkResponse link = ApiClient.asLink(created);
        HttpResponse<String> listed = api.listLinks(alice, 0, 100);
        HttpResponse<String> read = api.getLink(alice, link.code());
        Instant newExpiry = Instant.now().plus(90, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        HttpResponse<String> patched = api.updateExpiry(alice, link.code(), newExpiry);
        HttpResponse<String> clickedWhileLive = api.click(link.code());
        HttpResponse<String> deleted = api.deleteLink(alice, link.code());

        LinkPage page = ApiClient.asPage(listed);
        assertAll(
                () -> assertEquals(201, created.statusCode(), created.body()),
                () -> assertEquals(Fixtures.TARGET_URL, link.longUrl()),
                () -> assertEquals(200, listed.statusCode(), listed.body()),
                () -> assertTrue(page.items().stream().anyMatch(i -> i.code().equals(link.code())),
                        "their own new link is in their own list"),
                () -> assertEquals(200, read.statusCode(), read.body()),
                () -> assertEquals(200, patched.statusCode(), patched.body()),
                () -> assertEquals(newExpiry, ApiClient.asLink(patched).expiresAt(),
                        "the expiry they asked for is the expiry they got"),
                () -> assertEquals(302, clickedWhileLive.statusCode(), clickedWhileLive.body()),
                () -> assertEquals(204, deleted.statusCode(), deleted.body()),
                () -> assertTrue(
                        observeUntil(() -> api.click(link.code()).statusCode() == 404,
                                        Fixtures.TAKEDOWN_BOUND)
                                .isPresent(),
                        "and the delete takes effect within the published bound, as before"));
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
        String before = storedPasswordHash(Fixtures.ALICE.email())
                .orElseThrow(() -> new AssertionError("the seeded account holds no credential"));

        for (int i = 0; i < 3; i++) {
            givenAccount();
        }

        String after = storedPasswordHash(Fixtures.ALICE.email())
                .orElseThrow(() -> new AssertionError("the seeded account lost its credential"));
        HttpResponse<String> stillSignsIn =
                api.signIn(Fixtures.ALICE.email(), Fixtures.ALICE.plaintext());
        assertAll(
                () -> assertEquals(before, after,
                        "creating other accounts must not rewrite a seeded credential"),
                () -> assertEquals(1, storedAccountsNamed(Fixtures.ALICE.email()).size(),
                        "nor add a second row for the same address"),
                () -> assertEquals(200, stillSignsIn.statusCode(), stillSignsIn.body()),
                () -> assertEquals(Fixtures.ALICE.id(), ApiClient.asSession(stillSignsIn).customerId()));
    }

    // ---- helpers ----------------------------------------------------------

    /** The address with its first character upper-cased: a case variant, nothing else. */
    private String capitalise(String email) {
        return email.substring(0, 1).toUpperCase(Locale.ROOT) + email.substring(1);
    }
}
