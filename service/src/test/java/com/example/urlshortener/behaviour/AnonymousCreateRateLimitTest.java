package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.AnonymousLinkResponse;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The existing anti-abuse system governs anonymous creation (AC14).
 *
 * <p>The requirement is blunt about this: a path that ignores the anti-abuse
 * system is worse than not shipping the feature. So there are two claims, and
 * they need different setups. That anonymous creation is throttled at all is the
 * easy one. That "the anonymous path cannot be used to bypass the limits that
 * apply to authenticated creation" is the one worth designing for: it means the
 * two buckets are separate keys in separate namespaces, so neither can spend the
 * other's tokens, and the unauthenticated route is never the cheaper way to mint
 * links.
 *
 * <p>Both limits are driven down to numbers a test can reach. The anonymous
 * bucket is keyed by client address and every test in this suite comes from
 * loopback, so the bucket is emptied before each behaviour rather than inherited
 * from the last one.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            Fixtures.RATE_LIMIT_ENABLED_KEY + "=true",
            Fixtures.ANONYMOUS_CREATE_LIMIT_KEY + "=5",
            Fixtures.WRITE_LIMIT_KEY + "=5"
        })
class AnonymousCreateRateLimitTest extends AbstractIntegrationTest {

    /** The capacity this class configures for both buckets, quoted once. */
    private static final int CAPACITY = 5;

    @BeforeEach
    void startFromEmptyBuckets() {
        resetSharedTierState();
    }

    /**
     * Creating anonymously faster than the limit allows starts being refused with
     * 429 {@code rate_limited} once the bucket is empty, and the refusal carries
     * {@code Retry-After} in whole seconds and never 0 - a client told to retry
     * immediately is not being throttled.
     *
     * <p>Demonstrates: AC14.
     */
    @Test
    void anonymousCreationIsThrottledOnceItsBucketIsEmpty() {
        List<HttpResponse<String>> burst = createAnonymouslyRepeatedly(CAPACITY + 3);

        long accepted = burst.stream().filter(r -> r.statusCode() == 201).count();
        HttpResponse<String> throttled = burst.stream()
                .filter(r -> r.statusCode() == 429)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "an unauthenticated write path was never throttled: " + statuses(burst)));
        String retryAfter = ApiClient.header(throttled, Fixtures.RETRY_AFTER)
                .orElseThrow(() -> new AssertionError("a 429 must say when to come back"));
        assertAll(
                () -> assertEquals(201, burst.get(0).statusCode(),
                        "the first create is served: " + burst.get(0).body()),
                () -> assertTrue(accepted <= CAPACITY,
                        "no more than the bucket holds were accepted: " + statuses(burst)),
                () -> assertEquals(burst.size(), accepted + burst.stream()
                        .filter(r -> r.statusCode() == 429).count(),
                        "every answer was a 201 or a 429: " + statuses(burst)),
                () -> assertEquals(Fixtures.RATE_LIMITED, ApiClient.asError(throttled).error()),
                () -> assertEquals("Too many requests.", ApiClient.asError(throttled).message()),
                () -> assertTrue(retryAfter.matches("\\d+"), "whole seconds: " + retryAfter),
                () -> assertTrue(Long.parseLong(retryAfter) >= 1, "never 0: " + retryAfter));
    }

    /**
     * A throttled anonymous create mints nothing: no more links exist than the
     * bucket allowed through, and no code comes back to be clicked. The limit
     * exists to bound the storage burn, not to shape the reply.
     *
     * <p>Demonstrates: AC14.
     */
    @Test
    void aThrottledAnonymousCreateMintsNoLink() {
        List<HttpResponse<String>> burst = createAnonymouslyRepeatedly(CAPACITY + 3);

        Set<String> codesHandedBack = new LinkedHashSet<>();
        List<HttpResponse<String>> refusals = new ArrayList<>();
        for (HttpResponse<String> response : burst) {
            if (response.statusCode() == 201) {
                codesHandedBack.add(ApiClient.asAnonymousLink(response).code());
            } else {
                refusals.add(response);
            }
        }
        assertAll(
                () -> assertTrue(!refusals.isEmpty(), "the bucket was never emptied: " + statuses(burst)),
                () -> assertTrue(codesHandedBack.size() <= CAPACITY,
                        "the storage burn is bounded by the bucket: " + codesHandedBack),
                () -> assertAll(refusals.stream().map(refused -> (Executable) () -> assertAll(
                        () -> assertEquals(429, refused.statusCode(), refused.body()),
                        () -> assertFalse(ApiClient.asTree(refused).has("code"),
                                "a throttled create hands back no code: " + refused.body()),
                        () -> assertFalse(ApiClient.asTree(refused).has("shortUrl"),
                                refused.body())))),
                () -> assertAll(codesHandedBack.stream().map(code -> (Executable) () -> assertEquals(
                        302, api.click(code).statusCode(),
                        "every code that was handed back is a real link: " + code))));
    }

    /**
     * Exhausting the anonymous bucket leaves authenticated creation served: a
     * signed-in customer creating from the same address is unaffected. The buckets
     * are keyed differently and namespaced separately, so an anonymous flood
     * cannot be used to deny customers their own limit.
     *
     * <p>Demonstrates: AC14, AC17.
     */
    @Test
    void exhaustingTheAnonymousBucketLeavesAuthenticatedCreationServed() {
        String alice = alice();
        List<HttpResponse<String>> anonymousBurst = createAnonymouslyRepeatedly(CAPACITY + 3);

        HttpResponse<String> asACustomer = api.createLink(alice, Fixtures.OTHER_TARGET_URL);

        assertAll(
                () -> assertTrue(anonymousBurst.stream().anyMatch(r -> r.statusCode() == 429),
                        "the anonymous bucket really was emptied: " + statuses(anonymousBurst)),
                () -> assertEquals(201, asACustomer.statusCode(),
                        "an anonymous flood from this address must not spend a customer's write "
                                + "tokens: " + asACustomer.body()),
                () -> assertEquals(302,
                        api.click(ApiClient.asLink(asACustomer).code()).statusCode(),
                        "and the link it created works"));
    }

    /**
     * The reverse, and the half AC14 actually names: a customer who has spent their
     * authenticated write limit gains nothing by switching to the anonymous path -
     * it meters them too, and refuses at its own capacity. Anonymous creation is
     * never the cheaper way to mint links.
     *
     * <p>Demonstrates: AC14.
     */
    @Test
    void aCustomerWhoHasSpentTheirWriteLimitCannotMintMoreLinksAnonymously() {
        String alice = alice();
        List<HttpResponse<String>> authenticatedBurst = new ArrayList<>();
        for (int i = 0; i < CAPACITY + 3; i++) {
            authenticatedBurst.add(api.createLink(alice, Fixtures.TARGET_URL + "&spent=" + i));
        }

        List<HttpResponse<String>> switchedToAnonymous = createAnonymouslyRepeatedly(CAPACITY + 3);

        long mintedAnonymously = switchedToAnonymous.stream().filter(r -> r.statusCode() == 201).count();
        assertAll(
                () -> assertTrue(authenticatedBurst.stream().anyMatch(r -> r.statusCode() == 429),
                        "the customer really did spend their write limit: "
                                + statuses(authenticatedBurst)),
                () -> assertTrue(switchedToAnonymous.stream().anyMatch(r -> r.statusCode() == 429),
                        "switching path does not buy an unmetered one: "
                                + statuses(switchedToAnonymous)),
                () -> assertTrue(mintedAnonymously <= CAPACITY,
                        "and it refuses at its own capacity, not at nothing: "
                                + statuses(switchedToAnonymous)));
    }

    /**
     * Throttling anonymous creation does not throttle clicks: while the create
     * bucket is empty, links already minted keep redirecting from the same
     * address. The click path has its own, far larger bucket and is the priority.
     *
     * <p>Demonstrates: AC14, AC15.
     */
    @Test
    void anEmptyAnonymousCreateBucketDoesNotAffectTheClickPath() {
        AnonymousLinkResponse minted = givenAnonymousLink();
        List<HttpResponse<String>> burst = createAnonymouslyRepeatedly(CAPACITY + 3);

        List<HttpResponse<String>> clicks = clickRepeatedly(minted.code(), 5);

        assertAll(
                () -> assertTrue(burst.stream().anyMatch(r -> r.statusCode() == 429),
                        "the create bucket is empty: " + statuses(burst)),
                () -> assertTrue(clicks.stream().allMatch(r -> r.statusCode() == 302),
                        "clicks do not share a bucket with anonymous creation: " + statuses(clicks)),
                () -> assertTrue(clicks.stream().noneMatch(r -> r.statusCode() == 429),
                        "and the click path is the priority: " + statuses(clicks)),
                () -> assertEquals(Fixtures.TARGET_URL,
                        ApiClient.header(clicks.get(clicks.size() - 1), Fixtures.LOCATION).orElse(null)));
    }

    // ---- helpers ----------------------------------------------------------

    /** The status codes of a run of responses, for a failure message worth reading. */
    private String statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(r -> String.valueOf(r.statusCode())).toList().toString();
    }
}
