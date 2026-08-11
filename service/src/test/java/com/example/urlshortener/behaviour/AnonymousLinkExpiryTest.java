package com.example.urlshortener.behaviour;

import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * An anonymous link stops working when its month is up (AC10).
 *
 * <p><strong>How expiry is reached here, and why it is different from every
 * other expiry test in this suite.</strong> An owned link can be created with a
 * short expiry, so {@code givenExpiredLink} sets one through the API and sits
 * through it. An anonymous link cannot: its expiry is the service's to set and
 * is never caller-supplied, which is the whole of AC10. So the service's own TTL
 * is configured down for this class - the property is part of the frozen
 * configuration surface precisely so that a test can do this - and the harness
 * waits out the three seconds. Nothing else in the suite may assume this
 * override, and a class that forgets it would sit for thirty days rather than
 * fail.
 *
 * <p>That the default really is a month is a separate behaviour, on the default
 * configuration, in {@code AnonymousLinkCreationTest}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = Fixtures.ANONYMOUS_TTL_KEY + "=" + Fixtures.SHORT_ANONYMOUS_TTL_VALUE)
class AnonymousLinkExpiryTest extends AbstractIntegrationTest {

    /**
     * The expiry the response reports is the configured TTL after the creation
     * instant, whatever that TTL is set to - the link's own record of when it
     * stops working, not a value derived at read time.
     *
     * <p>Demonstrates: AC10.
     */
    @Test
    void theExpiryReportedIsTheConfiguredTtlAfterCreation() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Right up to its expiry the code redirects: the link is live for the whole of
     * the period it was promised, and does not become unusable early because a
     * cache or a sweeper decided so.
     *
     * <p>Demonstrates: AC10, AC11.
     */
    @Test
    void anAnonymousLinkRedirectsUntilItsExpiryPasses() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Once the expiry has passed, the code answers 404 {@code not_found} on the
     * click path. Never 410: 410 says "this existed", which distinguishes an
     * expired code from one that was never issued and hands an enumerator a free
     * signal.
     *
     * <p>Demonstrates: AC10, AC13.
     */
    @Test
    void anExpiredAnonymousCodeAnswersNotFoundOnTheClickPath() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * That answer is byte-identical - status, body and cache headers - to the one
     * an unissued code gets, and to the one an expired owned link gets. Everything
     * unusable is one answer.
     *
     * <p>Demonstrates: AC10, AC13.
     */
    @Test
    void anExpiredAnonymousCodeIsIndistinguishableFromOneNeverIssued() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Nobody can move the expiry: a change of expiry naming the code answers 404
     * for every caller, signed in or not, including whoever created it, and the
     * link still stops working when it said it would.
     *
     * <p>Demonstrates: AC10, AC13.
     */
    @Test
    void noCallerCanMoveAnAnonymousLinksExpiry() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }

    /**
     * Expiry is not what the cache decides. A code that expires while a redirect
     * for it is cached stops redirecting within the same bound the rest of the
     * service is held to, rather than serving from cache until the entry ages out.
     *
     * <p>Demonstrates: AC10, AC17.
     */
    @Test
    void anAnonymousLinkStopsRedirectingEvenWhenItsRedirectWasCached() {
        org.junit.jupiter.api.Assertions.fail("not implemented");
    }
}
