package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Metering the second unauthenticated endpoint that writes to the database.
 *
 * <p>The requirement asks for rate limiting on anonymous creation and says
 * nothing about sign-up, but the non-functional constraint it does state - an
 * unauthenticated endpoint that writes must not become an unmetered storage burn
 * - applies here with more force: this one runs a memory-hard hash before the
 * insert, so it is a CPU and memory vector as well as a storage one. It is also
 * the only thing bounding the account-existence disclosure that the 409 in
 * {@code SignUpUniquenessTest} necessarily carries, which makes this bucket
 * load-bearing rather than decorative.
 *
 * <p>The limit is driven down to something a test can reach. The production
 * default is set so that an ordinary test never trips it, which is exactly why a
 * test that wants to see a 429 must say so.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            Fixtures.RATE_LIMIT_ENABLED_KEY + "=true",
            Fixtures.SIGN_UP_LIMIT_KEY + "=5"
        })
class SignUpRateLimitTest extends AbstractIntegrationTest {

    /** The capacity this class configures, quoted once so the assertions can name it. */
    private static final int CAPACITY = 5;

    /**
     * The buckets are keyed by client address and shared by every context in this
     * JVM, so a class that wants to observe a limit being reached starts from a
     * known state rather than from whatever the previous class left behind.
     */
    @BeforeEach
    void startFromEmptyBuckets() {
        resetSharedTierState();
    }

    /**
     * Sign-ups from one address faster than the limit allows start being refused
     * with 429 {@code rate_limited} once the bucket is empty, carrying
     * {@code Retry-After} in whole seconds and never 0.
     *
     * <p>Demonstrates: AC4.
     */
    @Test
    void repeatedSignUpsFromOneAddressAreThrottled() {
        List<HttpResponse<String>> attempts = signUpRepeatedly(CAPACITY + 3);

        long accepted = attempts.stream().filter(r -> r.statusCode() == 201).count();
        HttpResponse<String> throttled = attempts.stream()
                .filter(r -> r.statusCode() == 429)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "signing up faster than the limit was never refused: " + statuses(attempts)));
        String retryAfter = ApiClient.header(throttled, Fixtures.RETRY_AFTER)
                .orElseThrow(() -> new AssertionError("a 429 must say when to come back"));
        assertAll(
                () -> assertEquals(201, attempts.get(0).statusCode(),
                        "the first sign-up is served: " + attempts.get(0).body()),
                () -> assertTrue(accepted <= CAPACITY,
                        "no more than the bucket holds were accepted: " + statuses(attempts)),
                () -> assertEquals(Fixtures.RATE_LIMITED, ApiClient.asError(throttled).error()),
                () -> assertEquals("Too many requests.", ApiClient.asError(throttled).message()),
                () -> assertTrue(retryAfter.matches("\\d+"), "whole seconds: " + retryAfter),
                () -> assertTrue(Long.parseLong(retryAfter) >= 1,
                        "never 0 - a client told to retry immediately is not throttled: " + retryAfter));
    }

    /**
     * A throttled sign-up creates no account: the address it named is still free
     * afterwards, and no more accounts exist than the bucket allowed through. A
     * limiter that refused the response after the insert would be metering the
     * reply rather than the storage burn.
     *
     * <p>Demonstrates: AC4.
     */
    @Test
    void aThrottledSignUpCreatesNoAccount() {
        List<String> addresses = new ArrayList<>();
        List<HttpResponse<String>> attempts = new ArrayList<>();
        for (int i = 0; i < CAPACITY + 3; i++) {
            String email = Fixtures.uniqueEmail("carol");
            addresses.add(email);
            attempts.add(api.signUp(email, Fixtures.NEW_ACCOUNT_PASSWORD));
        }

        long accepted = attempts.stream().filter(r -> r.statusCode() == 201).count();
        long existing = addresses.stream().filter(email -> !storedAccountsNamed(email).isEmpty()).count();
        List<String> throttledAddresses = new ArrayList<>();
        for (int i = 0; i < attempts.size(); i++) {
            if (attempts.get(i).statusCode() == 429) {
                throttledAddresses.add(addresses.get(i));
            }
        }
        assertAll(
                () -> assertTrue(accepted <= CAPACITY, statuses(attempts)),
                () -> assertTrue(!throttledAddresses.isEmpty(),
                        "the bucket was never emptied: " + statuses(attempts)),
                () -> assertEquals(accepted, existing,
                        "exactly the accepted sign-ups exist, and no more: " + statuses(attempts)),
                () -> assertTrue(
                        throttledAddresses.stream().allMatch(email -> storedAccountsNamed(email).isEmpty()),
                        "an address named by a throttled sign-up is still free: " + throttledAddresses));
    }

    /**
     * The throttle applies to duplicate attempts as well as to new addresses, so
     * the 409 that tells a caller an account exists cannot be issued faster than
     * the bucket allows. This is the bound on the account enumeration AC6 forces,
     * and it is the reason the bucket is not merely defensive tidiness.
     *
     * <p>Demonstrates: AC4, AC6.
     */
    @Test
    void probingForExistingAccountNamesIsThrottledAtTheSameRate() {
        List<HttpResponse<String>> probes = new ArrayList<>();
        for (int i = 0; i < CAPACITY + 3; i++) {
            // Every one of these is a well-formed sign-up for an address that is
            // already taken: the shape of an enumeration sweep.
            probes.add(api.signUp(Fixtures.ALICE.email(), Fixtures.NEW_ACCOUNT_PASSWORD));
        }

        long disclosed = probes.stream().filter(r -> r.statusCode() == 409).count();
        long throttled = probes.stream().filter(r -> r.statusCode() == 429).count();
        assertAll(
                () -> assertEquals(409, probes.get(0).statusCode(),
                        "the first probe is answered: " + probes.get(0).body()),
                () -> assertTrue(throttled >= 1,
                        "duplicate attempts must spend the same bucket, or the 409 is an unmetered "
                                + "account-existence oracle: " + statuses(probes)),
                () -> assertTrue(disclosed <= CAPACITY,
                        "the disclosure is bounded by the bucket, not by the endpoint's willingness: "
                                + statuses(probes)),
                () -> assertEquals(probes.size(), disclosed + throttled,
                        "every answer was a 409 or a 429: " + statuses(probes)),
                () -> assertEquals(1, storedAccountsNamed(Fixtures.ALICE.email()).size(),
                        "and none of the probing created anything"));
    }

    /**
     * Signing up does not spend the sign-in bucket, and signing in does not spend
     * the sign-up one: they are separate buckets under separate keys, so
     * exhausting either leaves the other serving. Sign-in is an untouched endpoint
     * and must stay that way.
     *
     * <p>Demonstrates: AC5, AC17.
     */
    @Test
    void theSignUpBucketAndTheSignInBucketAreSeparate() {
        List<HttpResponse<String>> signUpBurst = signUpRepeatedly(CAPACITY + 3);
        HttpResponse<String> signInWithTheSignUpBucketEmpty =
                api.signIn(Fixtures.ALICE.email(), Fixtures.ALICE.plaintext());

        resetSharedTierState();
        List<HttpResponse<String>> signInBurst = new ArrayList<>();
        for (int i = 0; i < CAPACITY; i++) {
            signInBurst.add(api.signIn(Fixtures.ALICE.email(), Fixtures.ALICE.plaintext()));
        }
        HttpResponse<String> signUpAfterThoseSignIns =
                api.signUp(Fixtures.uniqueEmail("carol"), Fixtures.NEW_ACCOUNT_PASSWORD);

        assertAll(
                () -> assertTrue(signUpBurst.stream().anyMatch(r -> r.statusCode() == 429),
                        "the sign-up bucket really was emptied: " + statuses(signUpBurst)),
                () -> assertEquals(200, signInWithTheSignUpBucketEmpty.statusCode(),
                        "an empty sign-up bucket must not close sign-in: "
                                + signInWithTheSignUpBucketEmpty.body()),
                () -> assertTrue(signInBurst.stream().allMatch(r -> r.statusCode() == 200),
                        "the sign-ins themselves were served: " + statuses(signInBurst)),
                () -> assertEquals(201, signUpAfterThoseSignIns.statusCode(),
                        "and they spent no sign-up tokens - " + CAPACITY + " sign-ins would have "
                                + "emptied a shared bucket: " + signUpAfterThoseSignIns.body()));
    }

    // ---- helpers ----------------------------------------------------------

    /** Signs up for that many fresh addresses, as fast as one client can, keeping every answer. */
    private List<HttpResponse<String>> signUpRepeatedly(int howMany) {
        List<HttpResponse<String>> responses = new ArrayList<>(howMany);
        for (int i = 0; i < howMany; i++) {
            responses.add(api.signUp(Fixtures.uniqueEmail("carol"), Fixtures.NEW_ACCOUNT_PASSWORD));
        }
        return responses;
    }

    /** The status codes of a run of responses, for a failure message worth reading. */
    private String statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(r -> String.valueOf(r.statusCode())).toList().toString();
    }
}
