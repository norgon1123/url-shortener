package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.AnonymousLinkResponse;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
     * Starts from a full anonymous-create bucket, for the reason
     * {@code AnonymousLinkCreationTest} gives: the bucket is keyed by client
     * address and shared across every context in this JVM, and a class that
     * emptied it deliberately leaves it empty for whatever runs next. Every
     * behaviour here needs a create to succeed before it can wait for it to
     * expire. This runs before the first click of each test.
     */
    @BeforeEach
    void startFromFullBuckets() {
        resetSharedTierState();
    }

    /**
     * The expiry the response reports is the configured TTL after the creation
     * instant, whatever that TTL is set to - the link's own record of when it
     * stops working, not a value derived at read time.
     *
     * <p>Demonstrates: AC10.
     */
    @Test
    void theExpiryReportedIsTheConfiguredTtlAfterCreation() {
        AnonymousLinkResponse link = givenAnonymousLink();

        Duration lifetime = Duration.between(link.createdAt(), link.expiresAt());
        assertAll(
                () -> assertTrue(
                        lifetime.minus(Fixtures.SHORT_ANONYMOUS_TTL).abs()
                                .compareTo(Duration.ofSeconds(1)) < 0,
                        "the service's configured TTL decides, and it is set to "
                                + Fixtures.SHORT_ANONYMOUS_TTL + " here, but the link lives for "
                                + lifetime),
                () -> assertNotEquals(Fixtures.ANONYMOUS_LINK_TTL, lifetime,
                        "a hard-coded month would ignore the configuration entirely"),
                () -> assertTrue(link.expiresAt().isAfter(link.createdAt())));
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
        AnonymousLinkResponse link = givenAnonymousLink();

        HttpResponse<String> immediately = api.click(link.code());
        sleep(Fixtures.SHORT_ANONYMOUS_TTL.dividedBy(2));
        Instant halfWay = Instant.now();
        HttpResponse<String> partWayThrough = api.click(link.code());

        assertAll(
                () -> assertEquals(302, immediately.statusCode(),
                        "it works from the moment it is minted: " + immediately.body()),
                () -> assertTrue(halfWay.isBefore(link.expiresAt()),
                        "the second click really was made before the promised expiry: " + halfWay
                                + " against " + link.expiresAt()),
                () -> assertEquals(302, partWayThrough.statusCode(),
                        "and it is still live part way through the period it was promised: "
                                + partWayThrough.body()),
                () -> assertEquals(Fixtures.TARGET_URL,
                        ApiClient.header(partWayThrough, Fixtures.LOCATION).orElse(null)));
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
        AnonymousLinkResponse link = givenExpiredAnonymousLink();

        HttpResponse<String> clicked = api.click(link.code());

        assertAll(
                () -> assertTrue(Instant.now().isAfter(link.expiresAt()),
                        "the harness really waited past the expiry: " + link.expiresAt()),
                () -> assertEquals(404, clicked.statusCode(),
                        "an expired anonymous link stops redirecting: " + clicked.body()),
                () -> assertNotEquals(410, clicked.statusCode(),
                        "410 would say the code once existed"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, clicked.body()),
                () -> assertTrue(ApiClient.header(clicked, Fixtures.LOCATION).isEmpty(),
                        "and nothing still points at the target"));
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
        AnonymousLinkResponse expiredAnonymous = givenExpiredAnonymousLink();
        LinkResponse expiredOwned = givenExpiredLink(alice());

        HttpResponse<String> anonymous = api.click(expiredAnonymous.code());
        HttpResponse<String> neverIssued = api.click(Fixtures.UNISSUED_CODE);
        HttpResponse<String> owned = api.click(expiredOwned.code());

        assertAll(
                () -> assertEquals(neverIssued.statusCode(), anonymous.statusCode(), anonymous.body()),
                () -> assertEquals(neverIssued.body(), anonymous.body(),
                        "expired and never issued are one answer"),
                () -> assertEquals(owned.statusCode(), anonymous.statusCode()),
                () -> assertEquals(owned.body(), anonymous.body(),
                        "and so are an expired anonymous link and an expired owned one"),
                () -> assertEquals(
                        ApiClient.header(neverIssued, Fixtures.CACHE_CONTROL),
                        ApiClient.header(anonymous, Fixtures.CACHE_CONTROL),
                        "cacheability leaks nothing either"),
                () -> assertEquals(Optional.of(Fixtures.NO_STORE),
                        ApiClient.header(anonymous, Fixtures.CACHE_CONTROL)));
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
        AnonymousLinkResponse link = givenAnonymousLink();
        Instant muchLater = Instant.now().plus(365, ChronoUnit.DAYS);
        String alice = alice();
        String newcomer = sessionFor(givenAccount());

        HttpResponse<String> bySeededCustomer = api.updateExpiry(alice, link.code(), muchLater);
        HttpResponse<String> byNewCustomer = api.updateExpiry(newcomer, link.code(), muchLater);
        // A null bearer is "send no Authorization header": the creator of an
        // anonymous link has no account, so this is the caller AC10 is about.
        HttpResponse<String> withNoCredential = api.updateExpiry(null, link.code(), muchLater);

        awaitExpiryOf(link.expiresAt());
        HttpResponse<String> afterTheOriginalExpiry = api.click(link.code());
        assertAll(
                () -> assertEquals(404, bySeededCustomer.statusCode(), bySeededCustomer.body()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, bySeededCustomer.body()),
                () -> assertEquals(404, byNewCustomer.statusCode(), byNewCustomer.body()),
                () -> assertEquals(401, withNoCredential.statusCode(),
                        "the management API is still authenticated, and its 401 comes before any "
                                + "lookup: " + withNoCredential.body()),
                () -> assertEquals(404, afterTheOriginalExpiry.statusCode(),
                        "and the link stopped working exactly when it said it would: "
                                + afterTheOriginalExpiry.body()));
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
        AnonymousLinkResponse link = givenAnonymousLink();
        // Clicked first, so whatever cache exists is holding this link when its
        // expiry passes. Deliberately no evictResolutionCache() anywhere here: a
        // test that cleared the cache itself would pass against a service that
        // served a dead link from cache for as long as the entry lived.
        assertEquals(302, api.click(link.code()).statusCode(), "it redirected before expiring");

        awaitInstant(link.expiresAt().plusMillis(500));
        Optional<Duration> stoppedAfter =
                observeUntil(() -> api.click(link.code()).statusCode() == 404, Fixtures.TAKEDOWN_BOUND);

        assertAll(
                () -> assertTrue(stoppedAfter.isPresent(),
                        "a cached redirect for an expired anonymous link was still being served "
                                + Fixtures.TAKEDOWN_BOUND + " after its expiry"),
                () -> assertTrue(stoppedAfter.orElseThrow().compareTo(Fixtures.TAKEDOWN_BOUND) < 0,
                        "it stopped after " + stoppedAfter.orElse(null)),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, api.click(link.code()).body(),
                        "and answers the one not-found body afterwards"));
    }
}
