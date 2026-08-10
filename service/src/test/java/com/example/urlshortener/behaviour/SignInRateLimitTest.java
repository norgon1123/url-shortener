package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.Fixtures;
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
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
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
