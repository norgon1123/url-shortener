package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.AnonymousLinkResponse;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.example.urlshortener.support.TestInfrastructure;

/**
 * What the anonymous path does while a dependency is unavailable (AC15).
 *
 * <p>The rule the service already follows is that degradation is spent on
 * refusing new links, never on serving clicks, and AC15 says the anonymous path
 * is subject to it exactly as the authenticated one is. The outage is produced
 * the way the existing degradation tests produce it: the harness pauses the
 * shared Redis tier, which keeps the port mapping so the recovered half can be
 * observed too.
 *
 * <p>Note the tolerance these behaviours have to keep. A create during the outage
 * may answer 201 or 503 - the threat checker fails open by deliberate
 * configuration, so whether creation survives depends on which dependency the
 * create needed - and the claim is about which answers are permitted, not about
 * forcing one. What is not permitted is any other 5xx, and any degradation of the
 * click path.
 *
 * <p>Every method carries a timeout: the failure being looked for is a path that
 * hangs on an unreachable dependency instead of degrading, and a hang with no
 * timeout is a suite that never finishes rather than a test that fails.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class AnonymousCreateDegradationTest extends AbstractIntegrationTest {

    /**
     * Starts from full buckets, before any outage. The anonymous-create bucket is
     * keyed by client address and another class empties it deliberately; a create
     * refused as throttled would satisfy "not 201" here and prove nothing about
     * degradation. Sessions and links are established before the pause for the
     * same reason the existing degradation class does it: creating is the
     * operation the design is willing to sacrifice.
     */
    @BeforeEach
    void startFromFullBuckets() {
        resetSharedTierState();
    }

    /** Whatever a behaviour does, the shared tier is running again before the next class. */
    @AfterEach
    void restoreSharedTier() {
        TestInfrastructure.resumeCounterTier();
    }

    /**
     * With the shared tier unreachable, an anonymous create either succeeds or is
     * refused with 503 {@code service_unavailable} - the same envelope the
     * authenticated create path uses - and never with any other server error.
     *
     * <p>Demonstrates: AC15.
     */
    @Test
    void anonymousCreationIsRefusedWithTheExistingErrorWhileADependencyIsUnavailable() {
        List<HttpResponse<String>> duringOutage = new ArrayList<>();

        TestInfrastructure.withCounterTierUnavailable(() -> {
            for (int i = 0; i < 3; i++) {
                duringOutage.add(api.createAnonymousLink(Fixtures.TARGET_URL + "&n=" + i));
            }
        });

        assertAll(
                () -> assertTrue(
                        duringOutage.stream().allMatch(r -> r.statusCode() == 201 || r.statusCode() == 503),
                        "a create either succeeds or is refused with 503, but answered "
                                + statuses(duringOutage)),
                () -> assertTrue(
                        duringOutage.stream()
                                .filter(r -> r.statusCode() != 201)
                                .allMatch(r -> Fixtures.SERVICE_UNAVAILABLE
                                        .equals(ApiClient.asError(r).error())),
                        "a refused create says service_unavailable: " + bodies(duringOutage)),
                () -> assertTrue(
                        duringOutage.stream()
                                .filter(r -> r.statusCode() == 503)
                                .allMatch(r -> "Temporarily unable to accept new links."
                                        .equals(ApiClient.asError(r).message())),
                        "with the message from the closed catalogue: " + bodies(duringOutage)),
                () -> assertTrue(
                        duringOutage.stream().noneMatch(r -> r.statusCode() >= 500 && r.statusCode() != 503),
                        "and never with any other server error: " + statuses(duringOutage)));
    }

    /**
     * The two create paths degrade together: posting the same target to both during
     * the outage produces the same verdict, so the anonymous route neither
     * survives an outage the authenticated one does not nor fails one it survives.
     *
     * <p>Demonstrates: AC15.
     */
    @Test
    void bothCreatePathsDegradeTheSameWay() {
        // Signed in before the pause: obtaining a session is itself an operation
        // the design is willing to sacrifice, and it is not the subject here.
        String alice = alice();
        AtomicReference<HttpResponse<String>> anonymously = new AtomicReference<>();
        AtomicReference<HttpResponse<String>> asACustomer = new AtomicReference<>();

        TestInfrastructure.withCounterTierUnavailable(() -> {
            anonymously.set(api.createAnonymousLink(Fixtures.OTHER_TARGET_URL));
            asACustomer.set(api.createLink(alice, Fixtures.OTHER_TARGET_URL));
        });

        HttpResponse<String> anonymous = anonymously.get();
        HttpResponse<String> authenticated = asACustomer.get();
        assertAll(
                () -> assertEquals(authenticated.statusCode(), anonymous.statusCode(),
                        "the anonymous route neither survives an outage the authenticated one does "
                                + "not nor fails one it survives: authenticated "
                                + authenticated.statusCode() + " " + authenticated.body()
                                + ", anonymous " + anonymous.statusCode() + " " + anonymous.body()),
                () -> assertTrue(
                        anonymous.statusCode() == 201 || anonymous.statusCode() == 503,
                        "and the shared verdict is one of the two documented ones: "
                                + anonymous.statusCode()),
                () -> assertTrue(
                        anonymous.statusCode() != 503
                                || authenticated.body().equals(anonymous.body()),
                        "with the same body when it is a refusal: " + anonymous.body()));
    }

    /**
     * While anonymous creation is being refused, clicks keep being served -
     * including clicks on links that were created anonymously before the outage,
     * with the right target and the right cache headers. This is the sentence AC15
     * is made of.
     *
     * <p>Demonstrates: AC15, AC11.
     */
    @Test
    void clicksOnAnonymousLinksKeepBeingServedWhileCreationIsRefused() {
        AnonymousLinkResponse minted = givenAnonymousLink(Fixtures.OTHER_TARGET_URL);
        AtomicReference<HttpResponse<String>> creation = new AtomicReference<>();
        List<HttpResponse<String>> clicks = new ArrayList<>();

        TestInfrastructure.withCounterTierUnavailable(() -> {
            creation.set(api.createAnonymousLink(Fixtures.TARGET_URL));
            for (int i = 0; i < 5; i++) {
                clicks.add(api.click(minted.code()));
            }
        });

        assertAll(
                () -> assertTrue(clicks.stream().allMatch(r -> r.statusCode() == 302),
                        "an anonymous link keeps redirecting throughout: " + statuses(clicks)),
                () -> assertTrue(
                        clicks.stream().allMatch(r -> Optional.of(Fixtures.OTHER_TARGET_URL)
                                .equals(ApiClient.header(r, Fixtures.LOCATION))),
                        "to the right target, not a stale or empty one"),
                () -> assertTrue(
                        clicks.stream().allMatch(r -> Optional.of(Fixtures.NO_STORE)
                                .equals(ApiClient.header(r, Fixtures.CACHE_CONTROL))),
                        "and a degraded redirect is still uncacheable"),
                () -> assertTrue(
                        creation.get().statusCode() == 201 || creation.get().statusCode() == 503,
                        "while creation is the thing allowed to be refused: "
                                + creation.get().statusCode()),
                () -> assertNotEquals(503, clicks.get(0).statusCode(),
                        "503 exists on the create paths and on no other operation"));
    }

    /**
     * Nothing on the click path answers with a server error during the outage, for
     * an anonymous code any more than for an owned one - not a live code, not an
     * unknown one. 503 exists on the create paths and on no other operation.
     *
     * <p>Demonstrates: AC15.
     */
    @Test
    void theClickPathNeverAnswersWithAServerErrorForAnAnonymousCode() {
        AnonymousLinkResponse live = givenAnonymousLink();
        List<HttpResponse<String>> onALiveAnonymousCode = new ArrayList<>();
        List<HttpResponse<String>> onAnUnknownCode = new ArrayList<>();

        TestInfrastructure.withCounterTierUnavailable(() -> {
            for (int i = 0; i < 5; i++) {
                onALiveAnonymousCode.add(api.click(live.code()));
                onAnUnknownCode.add(api.click(Fixtures.UNISSUED_CODE));
            }
        });

        assertAll(
                () -> assertTrue(onALiveAnonymousCode.stream().noneMatch(r -> r.statusCode() >= 500),
                        "a live anonymous code answered " + statuses(onALiveAnonymousCode)),
                () -> assertTrue(onALiveAnonymousCode.stream().allMatch(r -> r.statusCode() == 302),
                        "it must still redirect: " + statuses(onALiveAnonymousCode)),
                () -> assertTrue(onAnUnknownCode.stream().noneMatch(r -> r.statusCode() >= 500),
                        "an unknown code answered " + statuses(onAnUnknownCode)),
                () -> assertTrue(onAnUnknownCode.stream().allMatch(r -> r.statusCode() == 404),
                        "and still answers the single 404: " + statuses(onAnUnknownCode)),
                () -> assertTrue(
                        onAnUnknownCode.stream().allMatch(r -> Fixtures.NOT_FOUND_BODY.equals(r.body())),
                        "with the body it has when everything is healthy"));
    }

    /**
     * When the dependency comes back, anonymous creation works again with no
     * intervention, and the link it then mints redirects. A degraded mode nobody
     * recovers from is an outage.
     *
     * <p>Demonstrates: AC15.
     */
    @Test
    void anonymousCreationRecoversWhenTheDependencyReturns() {
        AtomicReference<HttpResponse<String>> duringOutage = new AtomicReference<>();

        TestInfrastructure.withCounterTierUnavailable(
                () -> duringOutage.set(api.createAnonymousLink(Fixtures.TARGET_URL)));

        // No restart, no flush, no intervention of any kind: the only thing that
        // changed is that the dependency is answering again.
        Optional<Duration> recoveredAfter = observeUntil(
                () -> api.createAnonymousLink(Fixtures.TARGET_URL).statusCode() == 201,
                Duration.ofSeconds(30));
        HttpResponse<String> afterRecovery = api.createAnonymousLink(Fixtures.OTHER_TARGET_URL);

        assertAll(
                () -> assertTrue(
                        duringOutage.get().statusCode() == 201 || duringOutage.get().statusCode() == 503,
                        "the outage half answered " + duringOutage.get().statusCode()),
                () -> assertTrue(recoveredAfter.isPresent(),
                        "anonymous creation never recovered after the dependency returned"),
                () -> assertEquals(201, afterRecovery.statusCode(),
                        "and it keeps working: " + afterRecovery.body()),
                () -> assertEquals(302,
                        api.click(ApiClient.asAnonymousLink(afterRecovery).code()).statusCode(),
                        "the link it mints redirects like any other"));
    }

    // ---- helpers ----------------------------------------------------------

    /** The status codes of a run of responses, for a failure message worth reading. */
    private String statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(r -> String.valueOf(r.statusCode())).toList().toString();
    }

    /** The bodies of a run of responses, for when the status alone does not explain it. */
    private String bodies(List<HttpResponse<String>> responses) {
        return responses.stream().map(HttpResponse::body).toList().toString();
    }
}
