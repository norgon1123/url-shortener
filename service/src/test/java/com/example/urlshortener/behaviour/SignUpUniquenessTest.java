package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Two people never end up with the same account name (AC6).
 *
 * <p>The requirement puts the whole weight on the concurrent case - "however
 * close together they try" - which is why this is a class of its own and why the
 * harness fires its attempts from a released latch rather than a loop. A
 * read-then-write implementation passes every sequential behaviour here and
 * fails the concurrent one, and that is the only test in the suite that can tell
 * the two apart.
 *
 * <p>The refusal is 409 {@code account_unavailable}. It is worth a reviewer
 * knowing that this is the one status in the catalogue that discloses whether an
 * account exists, in a service that goes to deliberate lengths elsewhere not to:
 * AC6 requires a visible refusal, and there is no visible refusal that is not an
 * oracle. What bounds the disclosure is the IP-keyed sign-up bucket, which is why
 * that bucket has behaviours of its own.
 */
class SignUpUniquenessTest extends AbstractIntegrationTest {

    /** The 409 body, byte for byte, from the closed catalogue in the frozen contract. */
    private static final String ACCOUNT_UNAVAILABLE_BODY =
            "{\"error\":\"account_unavailable\",\"message\":\"That account name is not available.\"}";

    /**
     * Starts from a full sign-up bucket.
     *
     * <p>The bucket is keyed by client address, one address serves the whole
     * suite, and the concurrent behaviour below fires several sign-ups at once. A
     * bucket drained by an earlier class would answer 429 where these expect 201
     * or 409, and the failure would read as a defect in uniqueness. Throttling has
     * its own class; here it is a precondition. No click delta is discarded: this
     * runs before the first click of every test here, and there are none.
     */
    @BeforeEach
    void startFromFullBuckets() {
        resetSharedTierState();
    }

    /**
     * A second sign-up for an address that already has an account is refused with
     * 409 {@code account_unavailable}, and storage still holds exactly one
     * account under that name.
     *
     * <p>Demonstrates: AC6.
     */
    @Test
    void aSecondSignUpForAnExistingAccountNameIsRefused() {
        String email = Fixtures.uniqueEmail("carol");
        HttpResponse<String> first = api.signUp(email, Fixtures.NEW_ACCOUNT_PASSWORD);

        HttpResponse<String> second = api.signUp(email, Fixtures.NEW_ACCOUNT_PASSWORD);

        assertAll(
                () -> assertEquals(201, first.statusCode(), first.body()),
                () -> assertEquals(409, second.statusCode(),
                        "a name that is taken is a conflict, not a bad request: " + second.body()),
                () -> assertEquals(Fixtures.ACCOUNT_UNAVAILABLE, ApiClient.asError(second).error()),
                () -> assertEquals(
                        "That account name is not available.", ApiClient.asError(second).message()),
                () -> assertEquals(1, storedAccountsNamed(email).size(),
                        "the stored data never contains two accounts with one name"));
    }

    /**
     * A case variant of an existing address is refused too. Uniqueness is over the
     * lower-cased address, because sign-in already treats case variants as one
     * account: allowing both to insert would leave a second account nobody can
     * sign in to, and a sign-in for either that fails on a non-unique result.
     *
     * <p>Demonstrates: AC6.
     */
    @Test
    void aCaseVariantOfAnExistingAccountNameIsRefused() {
        String email = Fixtures.uniqueEmail("carol");
        Fixtures.NewAccount held = givenAccount(email, Fixtures.NEW_ACCOUNT_PASSWORD);

        HttpResponse<String> shouted =
                api.signUp(email.toUpperCase(Locale.ROOT), "a-completely-different-password");

        HttpResponse<String> stillSignsIn = api.signIn(email, Fixtures.NEW_ACCOUNT_PASSWORD);
        assertAll(
                () -> assertEquals(409, shouted.statusCode(),
                        "uniqueness is over the lower-cased address: " + shouted.body()),
                () -> assertEquals(Fixtures.ACCOUNT_UNAVAILABLE, ApiClient.asError(shouted).error()),
                () -> assertEquals(1, storedAccountsNamed(email).size(),
                        "two rows differing only in case are what would make sign-in ambiguous"),
                () -> assertEquals(200, stillSignsIn.statusCode(),
                        "and the original account is unaffected: " + stillSignsIn.body()),
                () -> assertEquals(held.id(), ApiClient.asSession(stillSignsIn).customerId()));
    }

    /**
     * An address that arrived by migration is as taken as one that arrived through
     * the API: signing up as a seeded customer is refused with the same 409, and
     * the seeded account is untouched by the attempt.
     *
     * <p>Demonstrates: AC6, AC16.
     */
    @Test
    void aSeededAccountNameCannotBeTakenAgain() {
        HttpResponse<String> asAlice =
                api.signUp(Fixtures.ALICE.email(), "an-attackers-chosen-password");
        HttpResponse<String> asBobShouted = api.signUp(
                Fixtures.BOB.email().toUpperCase(Locale.ROOT), "another-attackers-password");

        HttpResponse<String> alicesSignIn =
                api.signIn(Fixtures.ALICE.email(), Fixtures.ALICE.plaintext());
        assertAll(
                () -> assertEquals(409, asAlice.statusCode(),
                        "an address that arrived by migration is taken: " + asAlice.body()),
                () -> assertEquals(Fixtures.ACCOUNT_UNAVAILABLE, ApiClient.asError(asAlice).error()),
                () -> assertEquals(409, asBobShouted.statusCode(), asBobShouted.body()),
                () -> assertEquals(1, storedAccountsNamed(Fixtures.ALICE.email()).size()),
                () -> assertEquals(1, storedAccountsNamed(Fixtures.BOB.email()).size()),
                () -> assertEquals(200, alicesSignIn.statusCode(),
                        "and the seeded account still signs in with its seeded credential: "
                                + alicesSignIn.body()),
                () -> assertEquals(Fixtures.ALICE.id(), ApiClient.asSession(alicesSignIn).customerId()),
                () -> assertEquals(401,
                        api.signIn(Fixtures.ALICE.email(), "an-attackers-chosen-password").statusCode(),
                        "the attempt did not install the attacker's password"));
    }

    /**
     * When several sign-ups for one address race, exactly one answers 201 and
     * every other answers 409 - no 500, no two winners - and storage holds exactly
     * one account for that address afterwards. The last claim is the one that
     * matters: a service that returned one 201 while writing two rows would answer
     * every request in this suite correctly and still have broken AC6.
     *
     * <p>Demonstrates: AC6.
     */
    @Test
    void concurrentSignUpsForOneAccountNameLeaveExactlyOneAccount() {
        String email = Fixtures.uniqueEmail("carol");

        List<HttpResponse<String>> raced =
                signUpConcurrently(email, Fixtures.NEW_ACCOUNT_PASSWORD, 6);

        long created = raced.stream().filter(r -> r.statusCode() == 201).count();
        long refused = raced.stream().filter(r -> r.statusCode() == 409).count();
        assertAll(
                () -> assertEquals(1L, created,
                        "exactly one of a simultaneous burst may win: " + statuses(raced)),
                () -> assertEquals(raced.size() - 1L, refused,
                        "and every other loses with 409, not with something else: " + statuses(raced)),
                () -> assertTrue(raced.stream().noneMatch(r -> r.statusCode() >= 500),
                        "a lost race is a refusal, not a server error: " + statuses(raced)),
                () -> assertTrue(
                        raced.stream()
                                .filter(r -> r.statusCode() == 409)
                                .allMatch(r -> Fixtures.ACCOUNT_UNAVAILABLE.equals(
                                        ApiClient.asError(r).error())),
                        "each loser gets the catalogue's refusal"),
                () -> assertEquals(1, storedAccountsNamed(email).size(),
                        "and the database holds one account, whatever the responses said"));
    }

    /**
     * A refused sign-up leaves the existing account exactly as it was: the
     * original password still signs in, and the password offered by the loser does
     * not. A duplicate that quietly overwrote the stored credential would be an
     * account takeover with a 409 on it.
     *
     * <p>Demonstrates: AC6, AC7, AC16.
     */
    @Test
    void aRefusedSignUpLeavesTheExistingAccountsCredentialUntouched() {
        String email = Fixtures.uniqueEmail("carol");
        String theOriginalPassword = "the-first-arrivals-password";
        String theLosersPassword = "the-second-arrivals-password";
        Fixtures.NewAccount held = givenAccount(email, theOriginalPassword);
        String credentialBefore = storedPasswordHash(email).orElseThrow();

        HttpResponse<String> refused = api.signUp(email, theLosersPassword);

        HttpResponse<String> withTheOriginal = api.signIn(email, theOriginalPassword);
        HttpResponse<String> withTheLosers = api.signIn(email, theLosersPassword);
        assertAll(
                () -> assertEquals(409, refused.statusCode(), refused.body()),
                () -> assertEquals(200, withTheOriginal.statusCode(),
                        "the account's own password still opens it: " + withTheOriginal.body()),
                () -> assertEquals(held.id(), ApiClient.asSession(withTheOriginal).customerId()),
                () -> assertEquals(401, withTheLosers.statusCode(),
                        "a duplicate sign-up is not a password change: " + withTheLosers.body()),
                () -> assertEquals(credentialBefore, storedPasswordHash(email).orElseThrow(),
                        "and the stored credential was not rewritten"));
    }

    /**
     * The refusal says nothing beyond "not available": not who holds the name, not
     * when it was taken, not whether it was lost to a registration two years ago
     * or to a request racing this one. The body is the catalogue entry and nothing
     * more, so the disclosure is exactly the one AC6 forces and no wider.
     *
     * <p>Demonstrates: AC6.
     */
    @Test
    void theRefusalNamesNeitherTheHolderNorWhenTheNameWasTaken() {
        String email = Fixtures.uniqueEmail("carol");
        Fixtures.NewAccount held = givenAccount(email, Fixtures.NEW_ACCOUNT_PASSWORD);

        HttpResponse<String> justTaken = api.signUp(email, Fixtures.NEW_ACCOUNT_PASSWORD);
        HttpResponse<String> takenByMigration =
                api.signUp(Fixtures.ALICE.email(), Fixtures.NEW_ACCOUNT_PASSWORD);

        assertAll(
                () -> assertEquals(409, justTaken.statusCode(), justTaken.body()),
                () -> assertEquals(ACCOUNT_UNAVAILABLE_BODY, justTaken.body(),
                        "the catalogue entry and nothing more"),
                () -> assertEquals(justTaken.body(), takenByMigration.body(),
                        "a name taken a moment ago and one taken by migration answer identically"),
                () -> assertFalse(ApiClient.asTree(justTaken).has("fields"),
                        "fields belongs to invalid_request only: " + justTaken.body()),
                () -> assertAll(List.of(
                                email,
                                held.id().toString(),
                                Fixtures.NEW_ACCOUNT_PASSWORD,
                                "createdAt",
                                "customerId")
                        .stream()
                        .map(secret -> (Executable) () -> assertFalse(
                                justTaken.body().toLowerCase(Locale.ROOT)
                                        .contains(secret.toLowerCase(Locale.ROOT)),
                                "the refusal must not carry " + secret + ": " + justTaken.body()))));
    }

    // ---- helpers ----------------------------------------------------------

    /** The status codes of a run of responses, for a failure message worth reading. */
    private String statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(r -> String.valueOf(r.statusCode())).toList().toString();
    }
}
