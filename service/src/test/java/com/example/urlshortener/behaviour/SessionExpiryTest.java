package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.api.SignInResponse;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
        String session = alice();
        LinkResponse link = givenLink(session);
        assertEquals(200, api.getLink(session, link.code()).statusCode(), "the session works while it lasts");

        // app.session.ttl is two seconds in this context.
        sleep(Duration.ofSeconds(3));
        HttpResponse<String> withAnExpiredSession = api.getLink(session, link.code());
        HttpResponse<String> withNoSession = api.getLink(null, link.code());

        assertAll(
                () -> assertEquals(401, withAnExpiredSession.statusCode(), withAnExpiredSession.body()),
                () -> assertEquals(withNoSession.statusCode(), withAnExpiredSession.statusCode()),
                () -> assertEquals(withNoSession.body(), withAnExpiredSession.body(),
                        "an expired credential is just one more unverifiable one"),
                () -> assertEquals("unauthorized", ApiClient.asError(withAnExpiredSession).error()));
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
        Instant before = Instant.now();

        SignInResponse session =
                ApiClient.asSession(api.signIn(Fixtures.ALICE.email(), Fixtures.ALICE.plaintext()));

        Duration advertised = Duration.between(before, session.expiresAt());
        assertAll(
                () -> assertTrue(
                        session.expiresAt().isAfter(before),
                        "the advertised expiry is an absolute instant in the future: " + advertised),
                () -> assertTrue(
                        advertised.compareTo(Duration.ofSeconds(30)) < 0,
                        "and it follows the configured two seconds rather than a literal 24 hours: "
                                + advertised));
    }
}
