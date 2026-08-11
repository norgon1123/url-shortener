package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
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
 * Throttling bulk junk-link creation (AC19), keyed by customer.
 *
 * <p>The write bucket is the one keyed by customer id rather than by address,
 * which makes it the only place this suite can show the second half of AC19 -
 * that while one source is being throttled, other customers keep being served.
 * Two seeded accounts and one limit are enough; two client addresses are not
 * something a test running from loopback can produce.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            Fixtures.RATE_LIMIT_ENABLED_KEY + "=true",
            Fixtures.WRITE_LIMIT_KEY + "=5"
        })
class WriteRateLimitTest extends AbstractIntegrationTest {

    @BeforeEach
    void startFromEmptyBuckets() {
        resetSharedTierState();
    }

    /**
     * A customer creating links far faster than the limit allows starts being
     * refused with 429 once their bucket is empty, and the links that were refused
     * do not exist afterwards - the storage burn is what the limit is for.
     *
     * <p>Demonstrates: AC19.
     */
    @Test
    void bulkLinkCreationByOneCustomerIsThrottled() {
        String alice = alice();
        long before = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();

        List<HttpResponse<String>> creates = createRepeatedly(alice, 12);

        long accepted = creates.stream().filter(r -> r.statusCode() == 201).count();
        long refused = creates.stream().filter(r -> r.statusCode() == 429).count();
        long after = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();
        assertAll(
                () -> assertEquals(201, creates.get(0).statusCode(), "the first create is served"),
                () -> assertTrue(refused >= 1, "creating faster than the limit must start being refused"),
                () -> assertTrue(accepted <= 5, "no more than the bucket holds were accepted: " + accepted),
                () -> assertEquals(12L, accepted + refused, "every answer was a 201 or a 429"),
                () -> assertEquals(before + accepted, after,
                        "the refused links do not exist afterwards - the storage burn is what the limit is for"));
    }

    /**
     * While one customer is being throttled, the other customer's creates, fetches
     * and deletes all succeed: the limit is per customer, so one abusive account
     * does not degrade anybody else.
     *
     * <p>Demonstrates: AC19, AC13.
     */
    @Test
    void anotherCustomerKeepsBeingServedWhileOneIsThrottled() {
        String alice = alice();
        String bob = bob();
        List<HttpResponse<String>> alicesBurst = createRepeatedly(alice, 12);

        HttpResponse<String> bobsCreate = api.createLink(bob, Fixtures.OTHER_TARGET_URL);
        LinkResponse bobsLink = ApiClient.asLink(bobsCreate);
        HttpResponse<String> bobsFetch = api.getLink(bob, bobsLink.code());
        HttpResponse<String> bobsDelete = api.deleteLink(bob, bobsLink.code());

        assertAll(
                () -> assertTrue(
                        alicesBurst.stream().anyMatch(r -> r.statusCode() == 429),
                        "the first customer's bucket is empty"),
                () -> assertEquals(201, bobsCreate.statusCode(), bobsCreate.body()),
                () -> assertEquals(200, bobsFetch.statusCode(), bobsFetch.body()),
                () -> assertEquals(204, bobsDelete.statusCode(), bobsDelete.body()));
    }

    /**
     * A throttled write carries {@code Retry-After} in whole seconds, at least
     * one, and the {@code rate_limited} body.
     *
     * <p>Demonstrates: AC19.
     */
    @Test
    void aThrottledWriteCarriesRetryAfterInWholeSeconds() {
        HttpResponse<String> throttled = createRepeatedly(alice(), 12).stream()
                .filter(r -> r.statusCode() == 429)
                .findFirst()
                .orElseThrow(() -> new AssertionError("bulk creation was never throttled"));

        String retryAfter = ApiClient.header(throttled, Fixtures.RETRY_AFTER)
                .orElseThrow(() -> new AssertionError("a 429 must say when to come back"));
        assertAll(
                () -> assertTrue(retryAfter.matches("\\d+"), "whole seconds: " + retryAfter),
                () -> assertTrue(Long.parseLong(retryAfter) >= 1, "never 0: " + retryAfter),
                () -> assertEquals("rate_limited", ApiClient.asError(throttled).error()),
                () -> assertEquals("Too many requests.", ApiClient.asError(throttled).message()));
    }

    /**
     * Clicks on existing links keep being served and counted while a customer's
     * write bucket is empty: the click path does not share a bucket with the write
     * path, and serving a click is preferred to accepting a link.
     *
     * <p>Demonstrates: AC19, AC20, AC22.
     */
    @Test
    void clicksAreUnaffectedWhileACustomersWriteBucketIsEmpty() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        List<HttpResponse<String>> burst = createRepeatedly(alice, 12);
        List<HttpResponse<String>> clicks = clickRepeatedly(link.code(), 10);

        assertAll(
                () -> assertTrue(
                        burst.stream().anyMatch(r -> r.statusCode() == 429),
                        "the write bucket is empty"),
                () -> assertTrue(
                        clicks.stream().allMatch(r -> r.statusCode() == 302),
                        "clicks do not share a bucket with writes"),
                () -> assertEquals(10L, reportedClickCount(alice, link.code()),
                        "and they are still counted"));
    }

    // ---- helpers ----------------------------------------------------------

    /** Creates links as fast as one client can, and keeps every answer. */
    private List<HttpResponse<String>> createRepeatedly(String bearer, int howMany) {
        List<HttpResponse<String>> responses = new ArrayList<>(howMany);
        for (int i = 0; i < howMany; i++) {
            responses.add(api.createLink(bearer, Fixtures.TARGET_URL + "&junk=" + i));
        }
        return responses;
    }
}
