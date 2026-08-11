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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one takedown path an unowned link has (AC13, AC17).
 *
 * <p>The abuse-report endpoint is unchanged by this change, and that is a
 * deliberate constraint rather than an oversight: it answers 202 for any
 * well-formed code, including one nobody owns, and a report blocks that link
 * within the published bound. Carving anonymous codes out would reintroduce the
 * existence oracle the blanket 202 exists to prevent, and an unowned link is the
 * one most in need of a takedown path - nobody can delete it.
 *
 * <p>There is a residual gap worth stating where a reviewer will see it: a report
 * requires a session, so only a signed-in caller can get an anonymous link taken
 * down. The last behaviour here pins that rather than pretending otherwise.
 */
class AnonymousLinkAbuseReportTest extends AbstractIntegrationTest {

    /** An ordinary reason, well inside the documented maximum length. */
    private static final String REASON = "Phishing page imitating a bank sign-in";

    /**
     * Starts from full buckets: the reporter bucket, which an earlier class empties
     * on purpose and which every behaviour here needs a token from, and the
     * anonymous-create bucket, which another class empties and which every
     * behaviour here needs a link from. This runs before the first click of each
     * test, so no click delta an assertion depends on is discarded.
     */
    @BeforeEach
    void startFromFullBuckets() {
        resetSharedTierState();
    }

    /**
     * Reporting an anonymous code is accepted with 202, exactly as reporting an
     * owned code is - the same status and the same body, so the response says
     * nothing about whether the code exists or who holds it.
     *
     * <p>Demonstrates: AC13, AC17.
     */
    @Test
    void reportingAnAnonymousCodeIsAcceptedLikeAnyOtherCode() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();
        String bob = bob();
        LinkResponse owned = givenLink(alice());

        HttpResponse<String> againstTheAnonymousCode = api.reportAbuse(bob, anonymous.code(), REASON);
        HttpResponse<String> againstAnOwnedCode = api.reportAbuse(bob, owned.code(), REASON);
        HttpResponse<String> againstAnUnissuedCode =
                api.reportAbuse(bob, Fixtures.UNISSUED_CODE, REASON);

        assertAll(
                () -> assertEquals(202, againstTheAnonymousCode.statusCode(),
                        "an unowned link is reportable like any other: "
                                + againstTheAnonymousCode.body()),
                () -> assertEquals(againstAnOwnedCode.statusCode(), againstTheAnonymousCode.statusCode()),
                () -> assertEquals(againstAnOwnedCode.body(), againstTheAnonymousCode.body(),
                        "the same body, so the response says nothing about ownership"),
                () -> assertEquals(againstAnUnissuedCode.body(), againstTheAnonymousCode.body(),
                        "nor about existence"),
                () -> assertNotEquals(404, againstTheAnonymousCode.statusCode(),
                        "a 404 here would be the existence oracle this endpoint exists to avoid"));
    }

    /**
     * A reported anonymous link stops redirecting within the published takedown
     * bound, measured from the response rather than assumed. The bound is the
     * figure the business is held to, and it applies to a link with no owner as
     * much as to one with.
     *
     * <p>Demonstrates: AC13, AC17.
     */
    @Test
    void aReportedAnonymousLinkStopsRedirectingWithinThePublishedBound() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();
        // Clicked first, so whatever cache exists is holding this link when the
        // report arrives - the case the bound is quoted for. And deliberately no
        // evictResolutionCache(): the takedown has to invalidate by itself.
        clickRepeatedly(anonymous.code(), 2);

        assertEquals(202, api.reportAbuse(bob(), anonymous.code(), REASON).statusCode());
        Optional<Duration> tookEffectAfter = observeUntil(
                () -> api.click(anonymous.code()).statusCode() == 404, Fixtures.TAKEDOWN_BOUND);

        assertAll(
                () -> assertTrue(tookEffectAfter.isPresent(),
                        "the reported anonymous link was still redirecting after the published bound "
                                + "of " + Fixtures.TAKEDOWN_BOUND),
                () -> assertTrue(tookEffectAfter.orElseThrow().compareTo(Fixtures.TAKEDOWN_BOUND) < 0,
                        "it stopped after " + tookEffectAfter.orElse(null)),
                () -> assertTrue(
                        ApiClient.header(api.click(anonymous.code()), Fixtures.LOCATION).isEmpty(),
                        "and nothing still points at the reported target"));
    }

    /**
     * Once blocked, the code answers the same 404 as an unissued one on the click
     * path: blocked is not a distinguishable state to anyone holding the link.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void aBlockedAnonymousCodeIsIndistinguishableFromOneNeverIssued() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();
        assertEquals(202, api.reportAbuse(bob(), anonymous.code(), REASON).statusCode());
        assertTrue(
                observeUntil(() -> api.click(anonymous.code()).statusCode() == 404,
                                Fixtures.TAKEDOWN_BOUND)
                        .isPresent(),
                "the report never took effect, so there is nothing to compare");

        HttpResponse<String> blocked = api.click(anonymous.code());
        HttpResponse<String> neverIssued = api.click(Fixtures.UNISSUED_CODE);

        assertAll(
                () -> assertEquals(404, blocked.statusCode(), blocked.body()),
                () -> assertNotEquals(410, blocked.statusCode(),
                        "410 would say the code once worked"),
                () -> assertEquals(neverIssued.statusCode(), blocked.statusCode()),
                () -> assertEquals(neverIssued.body(), blocked.body(),
                        "blocked is not a distinguishable state"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, blocked.body()),
                () -> assertEquals(
                        ApiClient.header(neverIssued, Fixtures.CACHE_CONTROL),
                        ApiClient.header(blocked, Fixtures.CACHE_CONTROL),
                        "and cacheability leaks nothing either"));
    }

    /**
     * A report still requires a session: an unauthenticated caller reporting an
     * anonymous code is refused as unauthenticated, unchanged from today. This is
     * the residual gap - the one link with no owner to complain about it can only
     * be reported by somebody with an account - and it is pinned so that it is a
     * decision on the record rather than a surprise.
     *
     * <p>Demonstrates: AC13, AC17.
     */
    @Test
    void reportingAnAnonymousCodeStillRequiresASession() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();

        HttpResponse<String> withNoCredential = api.reportAbuse(null, anonymous.code(), REASON);
        HttpResponse<String> withAForgedOne =
                api.reportAbuse(Fixtures.FORGED_BEARER, anonymous.code(), REASON);
        HttpResponse<String> againstAnUnissuedCode =
                api.reportAbuse(null, Fixtures.UNISSUED_CODE, REASON);

        assertAll(
                () -> assertEquals(401, withNoCredential.statusCode(),
                        "holding the link is not enough to get it taken down: "
                                + withNoCredential.body()),
                () -> assertEquals("unauthorized", ApiClient.asError(withNoCredential).error()),
                () -> assertEquals(401, withAForgedOne.statusCode(), withAForgedOne.body()),
                () -> assertEquals(againstAnUnissuedCode.body(), withNoCredential.body(),
                        "and the 401 comes before any lookup, so it says nothing about the code"),
                () -> assertEquals(302, api.click(anonymous.code()).statusCode(),
                        "the link is untouched by an unauthenticated report"));
    }
}
