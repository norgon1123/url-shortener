package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import com.example.urlshortener.support.TestInfrastructure;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What the service does while one of its dependencies is unavailable (AC20).
 *
 * <p>The stated preference is unambiguous: when the system cannot do both, a
 * click is served in preference to accepting a new link. That sentence only means
 * something if the failure can be produced, so the harness pauses the Redis
 * container - the tier that holds the click counters, the resolution cache and
 * the token buckets - and leaves PostgreSQL up. Pausing rather than stopping
 * keeps the port mapping, so the outage is reversible and the recovered half can
 * be observed too.
 *
 * <p>Every method here carries a timeout. The failure this class is looking for
 * is a click path that hangs on an unreachable dependency instead of degrading,
 * and a hang with no timeout is a suite that never finishes rather than a test
 * that fails.
 *
 * <p>Sessions and links are always obtained <em>before</em> the pause. Signing in
 * and creating are the operations the design is willing to sacrifice, so using
 * them to set a test up would confuse the precondition with the subject.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class DegradedDependencyTest extends AbstractIntegrationTest {

    /**
     * Belt and braces: whatever a test does, the shared tier is running again
     * before the next class starts. A paused container leaks into every later
     * test in the JVM and the resulting failures point everywhere except here.
     */
    @AfterEach
    void restoreSharedTier() {
        TestInfrastructure.resumeCounterTier();
    }

    /**
     * With the counting and caching tier unreachable, a click on a live link still
     * answers 302 with the right target: the redirect is served from PostgreSQL,
     * and the count is what degrades, not the click.
     *
     * <p>Demonstrates: AC20, AC2.
     */
    @Test
    void clicksAreStillServedWhileTheCountingTierIsUnavailable() {
        LinkResponse link = givenLink(alice(), Fixtures.OTHER_TARGET_URL);
        AtomicReference<HttpResponse<String>> duringOutage = new AtomicReference<>();

        TestInfrastructure.withCounterTierUnavailable(() -> duringOutage.set(api.click(link.code())));

        HttpResponse<String> click = duringOutage.get();
        assertAll(
                () -> assertEquals(302, click.statusCode(),
                        "a click is served from the durable store when the counting tier is gone"),
                () -> assertEquals(
                        Fixtures.OTHER_TARGET_URL,
                        ApiClient.header(click, Fixtures.LOCATION).orElse(null),
                        "and it is sent to the right target, not to a stale or empty one"),
                () -> assertEquals(Fixtures.NO_STORE,
                        ApiClient.header(click, Fixtures.CACHE_CONTROL).orElse(null),
                        "a degraded redirect is still uncacheable"));
    }

    /**
     * Nothing on the click path answers with a server error while that tier is
     * down - not for a live code, not for an unknown one. A 5xx on the click path
     * is the outcome AC20 exists to forbid.
     *
     * <p>Demonstrates: AC20, AC15.
     */
    @Test
    void theClickPathNeverAnswersWithAServerError() {
        LinkResponse live = givenLink(alice());
        List<HttpResponse<String>> onALiveCode = new ArrayList<>();
        List<HttpResponse<String>> onAnUnknownCode = new ArrayList<>();

        TestInfrastructure.withCounterTierUnavailable(() -> {
            for (int i = 0; i < 5; i++) {
                onALiveCode.add(api.click(live.code()));
                onAnUnknownCode.add(api.click(Fixtures.UNISSUED_CODE));
            }
        });

        assertAll(
                () -> assertTrue(
                        onALiveCode.stream().noneMatch(r -> r.statusCode() >= 500),
                        "a live code answered " + statuses(onALiveCode) + " while the tier was down"),
                () -> assertTrue(
                        onALiveCode.stream().allMatch(r -> r.statusCode() == 302),
                        "a live code must still redirect: " + statuses(onALiveCode)),
                () -> assertTrue(
                        onAnUnknownCode.stream().noneMatch(r -> r.statusCode() >= 500),
                        "an unknown code answered " + statuses(onAnUnknownCode)),
                () -> assertTrue(
                        onAnUnknownCode.stream().allMatch(r -> r.statusCode() == 404),
                        "an unknown code still answers the single 404: " + statuses(onAnUnknownCode)),
                () -> assertTrue(
                        onAnUnknownCode.stream().allMatch(r -> Fixtures.NOT_FOUND_BODY.equals(r.body())),
                        "with the same body it has when everything is healthy"));
    }

    /**
     * Requests are not refused as throttled because the limiter cannot reach its
     * store: the limiter fails open, since a dependency outage that turned every
     * click into a 429 would be a self-inflicted outage of the path being
     * protected.
     *
     * <p>Demonstrates: AC20, AC19.
     */
    @Test
    void throttlingFailsOpenRatherThanRefusingEveryClick() {
        LinkResponse live = givenLink(alice());
        List<HttpResponse<String>> clicks = new ArrayList<>();

        TestInfrastructure.withCounterTierUnavailable(() -> {
            for (int i = 0; i < 10; i++) {
                clicks.add(api.click(live.code()));
                clicks.add(api.click(Fixtures.UNISSUED_CODE));
            }
        });

        long throttled = clicks.stream().filter(r -> r.statusCode() == 429).count();
        assertAll(
                () -> assertEquals(0L, throttled,
                        "the limiter must fail open, not refuse everything it cannot count: "
                                + statuses(clicks)),
                () -> assertTrue(
                        clicks.stream().noneMatch(r -> r.statusCode() >= 500),
                        "and it must not fail loudly either: " + statuses(clicks)));
    }

    /**
     * When the tier comes back, clicks are counted again and the figure reported
     * afterwards includes everything counted before the outage. Clicks served
     * during the outage may be lost - that is the degraded mode the design chose,
     * and losing the click instead would be the wrong trade - but nothing counted
     * either side of it is.
     *
     * <p>Demonstrates: AC20, AC3.
     */
    @Test
    void countingResumesWhenTheTierReturnsWithoutLosingEarlierClicks() {
        String alice = alice();
        LinkResponse link = givenLink(alice);
        clickRepeatedly(link.code(), 3);
        long beforeTheOutage = reportedClickCount(alice, link.code());

        TestInfrastructure.withCounterTierUnavailable(() -> clickRepeatedly(link.code(), 2));

        long onRecovery = reportedClickCount(alice, link.code());
        clickRepeatedly(link.code(), 4);
        long afterRecovery = reportedClickCount(alice, link.code());
        assertAll(
                () -> assertEquals(3L, beforeTheOutage, "the three clicks before the outage were counted"),
                () -> assertTrue(
                        onRecovery >= beforeTheOutage,
                        "clicks counted before the outage must survive it: " + beforeTheOutage
                                + " became " + onRecovery),
                () -> assertEquals(4L, afterRecovery - onRecovery,
                        "counting resumes exactly when the tier returns"));
    }

    /**
     * If anything is refused while the tier is down it is link creation, with 503
     * {@code service_unavailable}, and never a click. This is the one place the
     * preference for serving clicks over accepting links is visible on the wire.
     *
     * <p>Demonstrates: AC20.
     */
    @Test
    void degradationIsSpentOnAcceptingLinksRatherThanOnServingClicks() {
        String alice = alice();
        LinkResponse live = givenLink(alice);
        AtomicReference<HttpResponse<String>> creation = new AtomicReference<>();
        AtomicReference<HttpResponse<String>> click = new AtomicReference<>();
        AtomicInteger clicksRefused = new AtomicInteger();

        TestInfrastructure.withCounterTierUnavailable(() -> {
            creation.set(api.createLink(alice, Fixtures.OTHER_TARGET_URL));
            click.set(api.click(live.code()));
            for (int i = 0; i < 5; i++) {
                if (api.click(live.code()).statusCode() != 302) {
                    clicksRefused.incrementAndGet();
                }
            }
        });

        int created = creation.get().statusCode();
        assertAll(
                // The click side of the preference is absolute.
                () -> assertEquals(302, click.get().statusCode(), "a click is never what gets refused"),
                () -> assertEquals(0, clicksRefused.get(), "and that holds for every click, not just the first"),
                () -> assertNotEquals(503, click.get().statusCode(),
                        "503 exists on create and on no other operation"),
                // The create side may be refused - but only in the documented way.
                () -> assertTrue(
                        created == 201 || created == 503,
                        "a create either succeeds or is refused with 503, but answered " + created
                                + ": " + creation.get().body()),
                () -> assertTrue(
                        created != 503 || "service_unavailable".equals(ApiClient.asError(creation.get()).error()),
                        "a refused create says service_unavailable: " + creation.get().body()),
                () -> assertTrue(
                        created != 503
                                || "Temporarily unable to accept new links."
                                        .equals(ApiClient.asError(creation.get()).message()),
                        "with the message from the closed catalogue: " + creation.get().body()));
    }

    // ---- helpers ----------------------------------------------------------

    /** The status codes of a run of responses, for a failure message worth reading. */
    private String statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(r -> String.valueOf(r.statusCode())).toList().toString();
    }
}
