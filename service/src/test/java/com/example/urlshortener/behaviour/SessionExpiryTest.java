package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What happens when a session runs out.
 *
 * <p>A session lasts 24 hours in production, which is not a thing a test can sit
 * through, so this class runs against its own application context with
 * {@code app.session.ttl} turned down to two seconds. That the lifetime is a
 * property at all is half the point: it is the blast radius of a leaked
 * credential, and it is configuration rather than code.
 *
 * <p>The whole {@code @SpringBootTest} annotation is repeated because the most
 * specific one wins outright; dropping {@code RANDOM_PORT} would leave nothing
 * listening.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = Fixtures.SESSION_TTL_KEY + "=PT2S")
class SessionExpiryTest extends AbstractIntegrationTest {

    /**
     * A session that has passed its expiry is refused with the same 401 and the
     * same {@code unauthorized} body as no session at all: expiry is one more
     * unverifiable credential, and the response does not say which kind it was.
     *
     * <p>Demonstrates: AC12, AC18.
     */
    @Test
    void anExpiredSessionIsRefusedLikeAnAbsentOne() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * The expiry advertised in the sign-in response is the configured lifetime
     * from now, so a client can tell when to sign in again without decoding the
     * credential, and changing the lifetime is a configuration change.
     *
     * <p>Demonstrates: AC18.
     */
    @Test
    void theAdvertisedExpiryFollowsTheConfiguredLifetime() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
