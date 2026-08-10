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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Throttling the click surface (AC19) without throttling a link that has gone
 * viral (AC22).
 *
 * <p>Two buckets, both keyed by client IP, and the whole design turns on them
 * being separate: an enumeration sweep is a long run of 404s while a hot link is
 * a long run of 302s, so the not-found bucket is set an order of magnitude
 * tighter than the click bucket. This class runs with the not-found bucket small
 * enough to empty in a few requests and the click bucket left generous, which is
 * the production shape in miniature.
 *
 * <p>Every test starts from an empty shared tier, because one Redis is shared by
 * the whole JVM and a bucket another test drained is still drained a second
 * later.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            Fixtures.RATE_LIMIT_ENABLED_KEY + "=true",
            Fixtures.NOT_FOUND_LIMIT_KEY + "=5",
            Fixtures.CLICK_LIMIT_KEY + "=1000"
        })
class ClickRateLimitTest extends AbstractIntegrationTest {

    @BeforeEach
    void startFromEmptyBuckets() {
        resetSharedTierState();
    }

    /**
     * A sweep of codes that do not resolve is refused once the not-found bucket is
     * empty: the first few answer 404 and the rest answer 429, so guessing costs
     * the attacker more than it costs us.
     *
     * <p>Demonstrates: AC19, AC16.
     */
    @Test
    void aSweepOfUnissuedCodesIsThrottled() {
        List<HttpResponse<String>> sweep = sweepUnissuedCodes(15);

        long notFound = sweep.stream().filter(r -> r.statusCode() == 404).count();
        long throttled = sweep.stream().filter(r -> r.statusCode() == 429).count();
        assertAll(
                () -> assertEquals(404, sweep.get(0).statusCode(), "the sweep starts by simply missing"),
                () -> assertTrue(throttled >= 1, "a sweep past the bucket must start being refused"),
                () -> assertTrue(
                        notFound <= 5,
                        "no more misses than the bucket holds were answered: " + notFound),
                () -> assertEquals(15L, notFound + throttled, "every answer was a 404 or a 429"));
    }

    /**
     * A throttled response carries {@code Retry-After} in whole seconds, at least
     * one - never zero, which would tell a client to come straight back and turn
     * the limiter into an amplifier.
     *
     * <p>Demonstrates: AC19.
     */
    @Test
    void aThrottledClickCarriesRetryAfterInWholeSeconds() {
        List<HttpResponse<String>> sweep = sweepUnissuedCodes(15);

        HttpResponse<String> throttled = sweep.stream()
                .filter(r -> r.statusCode() == 429)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the sweep was never throttled"));

        String retryAfter = ApiClient.header(throttled, Fixtures.RETRY_AFTER)
                .orElseThrow(() -> new AssertionError("a 429 must say when to come back"));
        assertAll(
                () -> assertTrue(retryAfter.matches("\\d+"), "whole seconds, not a date: " + retryAfter),
                () -> assertTrue(
                        Long.parseLong(retryAfter) >= 1,
                        "never 0, which would tell a client to come straight back: " + retryAfter));
    }

    /**
     * With the not-found bucket exhausted by a sweep, a real link on the same
     * address keeps being served with 302s and keeps being counted. Throttling the
     * scraper does not throttle the audience.
     *
     * <p>Demonstrates: AC22, AC19, AC3.
     */
    @Test
    void aRealLinkKeepsBeingServedWhileTheEnumerationBucketIsEmpty() {
        String alice = alice();
        LinkResponse viral = givenLink(alice);

        List<HttpResponse<String>> sweep = sweepUnissuedCodes(15);
        List<HttpResponse<String>> realClicks = clickRepeatedly(viral.code(), 8);

        assertAll(
                () -> assertTrue(
                        sweep.stream().anyMatch(r -> r.statusCode() == 429),
                        "the enumeration bucket was drained by the sweep"),
                () -> assertTrue(
                        realClicks.stream().allMatch(r -> r.statusCode() == 302),
                        "throttling the scraper must not throttle the audience"),
                () -> assertTrue(
                        realClicks.stream()
                                .allMatch(r -> Fixtures.TARGET_URL.equals(
                                        ApiClient.header(r, Fixtures.LOCATION).orElse(null))),
                        "and they go where they always did"),
                () -> assertEquals(8L, reportedClickCount(alice, viral.code()),
                        "and they are still counted"));
    }

    /**
     * A request refused with 429 is not counted as a click on anything: a
     * throttled sweep cannot move any link's reported figure.
     *
     * <p>Demonstrates: AC3, AC19.
     */
    @Test
    void aThrottledRequestIsNotCountedAsAClick() {
        String alice = alice();
        LinkResponse link = givenLink(alice);
        clickRepeatedly(link.code(), 3);
        long before = reportedClickCount(alice, link.code());

        List<HttpResponse<String>> sweep = sweepUnissuedCodes(20);

        assertAll(
                () -> assertEquals(3L, before),
                () -> assertTrue(
                        sweep.stream().filter(r -> r.statusCode() == 429).count() >= 1,
                        "the sweep was refused"),
                () -> assertEquals(3L, reportedClickCount(alice, link.code()),
                        "a refused request is not a click on anything"));
    }

    /**
     * A throttled click answers 429 with the {@code rate_limited} body and never a
     * 5xx: the click path is the one that must stay up, so refusing a request is
     * not the same as failing.
     *
     * <p>Demonstrates: AC19, AC20.
     */
    @Test
    void throttlingIsARefusalAndNeverAServerError() {
        List<HttpResponse<String>> sweep = sweepUnissuedCodes(20);

        HttpResponse<String> throttled = sweep.stream()
                .filter(r -> r.statusCode() == 429)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the sweep was never throttled"));

        assertAll(
                () -> assertTrue(
                        sweep.stream().noneMatch(r -> r.statusCode() >= 500),
                        "refusing a request is not the same as failing one"),
                () -> assertEquals("rate_limited", ApiClient.asError(throttled).error()),
                () -> assertEquals("Too many requests.", ApiClient.asError(throttled).message()),
                () -> assertTrue(
                        ApiClient.asError(throttled).fields() == null,
                        "fields belongs to invalid_request and nowhere else"));
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Requests codes that were never issued, one after another, the way an
     * enumeration sweep does. The codes are fresh each time so that nothing is
     * answered from a negative cache entry a previous test left behind.
     */
    private List<HttpResponse<String>> sweepUnissuedCodes(int howMany) {
        List<HttpResponse<String>> responses = new ArrayList<>(howMany);
        for (int i = 0; i < howMany; i++) {
            responses.add(api.click(UUID.randomUUID().toString().replace("-", "").substring(0, 22)));
        }
        return responses;
    }
}
