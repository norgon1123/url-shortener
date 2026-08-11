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
 * The limit on sign-in attempts (AC19), keyed by client address.
 *
 * <p>An unthrottled sign-in is an open credential-stuffing target, and AC17's
 * promise about stolen databases is worth little if the passwords can simply be
 * guessed at the front door. The limit is set to three here so it can be reached
 * in a few requests.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            Fixtures.RATE_LIMIT_ENABLED_KEY + "=true",
            Fixtures.SIGN_IN_LIMIT_KEY + "=3"
        })
class SignInRateLimitTest extends AbstractIntegrationTest {

    @BeforeEach
    void startFromEmptyBuckets() {
        resetSharedTierState();
    }

    /**
     * Repeated failed sign-ins from one address are refused with 429 once the
     * bucket is empty, so guessing a password is bounded by the limit rather than
     * by the attacker's bandwidth.
     *
     * <p>Demonstrates: AC19, AC17.
     */
    @Test
    void repeatedSignInAttemptsFromOneSourceAreThrottled() {
        List<HttpResponse<String>> attempts = guessTheirPassword(10);

        long refusedCredentials = attempts.stream().filter(r -> r.statusCode() == 401).count();
        long throttled = attempts.stream().filter(r -> r.statusCode() == 429).count();
        assertAll(
                () -> assertEquals(401, attempts.get(0).statusCode(), "the first guess is simply wrong"),
                () -> assertTrue(throttled >= 1, "guessing must stop being answered once the bucket is empty"),
                () -> assertTrue(
                        refusedCredentials <= 3,
                        "no more guesses than the bucket holds were considered: " + refusedCredentials),
                () -> assertEquals(10L, refusedCredentials + throttled, "every answer was a 401 or a 429"));
    }

    /**
     * A throttled sign-in answers 429 with {@code Retry-After}, and its body does
     * not say whether the credentials would have been accepted - the limit must
     * not become the oracle the 401 was careful not to be.
     *
     * <p>Demonstrates: AC19, AC17.
     */
    @Test
    void aThrottledSignInDisclosesNothingAboutTheCredentials() {
        guessTheirPassword(10);

        // The right password, offered once the bucket is empty: the answer must
        // say nothing about whether it would have been accepted.
        HttpResponse<String> withTheRightPassword =
                api.signIn(Fixtures.ALICE.email(), Fixtures.ALICE.plaintext());
        HttpResponse<String> withAWrongPassword = api.signIn(Fixtures.ALICE.email(), "still-wrong");

        String retryAfter = ApiClient.header(withTheRightPassword, Fixtures.RETRY_AFTER)
                .orElseThrow(() -> new AssertionError("a 429 must say when to come back"));
        assertAll(
                () -> assertEquals(429, withTheRightPassword.statusCode()),
                () -> assertEquals(429, withAWrongPassword.statusCode()),
                () -> assertEquals(withAWrongPassword.body(), withTheRightPassword.body(),
                        "the limit must not become the oracle the 401 was careful not to be"),
                () -> assertEquals("rate_limited", ApiClient.asError(withTheRightPassword).error()),
                () -> assertTrue(retryAfter.matches("\\d+"), "whole seconds: " + retryAfter),
                () -> assertTrue(Long.parseLong(retryAfter) >= 1, "never 0: " + retryAfter));
    }

    // ---- helpers ----------------------------------------------------------

    /** Credential stuffing, in miniature: the right account, the wrong password. */
    private List<HttpResponse<String>> guessTheirPassword(int howMany) {
        List<HttpResponse<String>> attempts = new ArrayList<>(howMany);
        for (int i = 0; i < howMany; i++) {
            attempts.add(api.signIn(Fixtures.ALICE.email(), "wrong-password-" + i));
        }
        return attempts;
    }
}
