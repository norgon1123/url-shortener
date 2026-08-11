package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.domain.LinkStatus;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reporting a link as abusive, and the takedown that follows (AC21, AC9).
 *
 * <p>A report blocks the link immediately: there is no moderation console in this
 * build, so there is nobody for a queued report to wait for. The endpoint is
 * authenticated and rate-limited per reporter, and that pair is the entire
 * defence against the feature being a cheap way to kill a competitor's links -
 * the limit itself is exercised in its own class, because it needs a bucket small
 * enough to empty.
 *
 * <p>It answers 202 for any well-formed code whether or not it resolves. A 404
 * here would be the existence oracle every other endpoint is careful not to be,
 * and this is the one endpoint that takes a code from an untrusted caller.
 */
class AbuseReportTest extends AbstractIntegrationTest {

    /** An ordinary reason, well inside the documented maximum length. */
    private static final String REASON = "Phishing page imitating a bank sign-in";

    /**
     * Starts from a full abuse-report bucket.
     *
     * <p>Not a rate-limit test, and that is exactly why this is here: the bucket
     * is keyed by reporter and lives in the one Redis every context in the JVM
     * shares, and {@code AbuseReportRateLimitTest} - which runs immediately before
     * this class, alphabetically - empties Bob's on purpose. The key carries no
     * capacity component, so it survives the context switch still empty and refills
     * at the default rate of one token a second; this class then reports as Bob
     * several times in well under a second and is answered 429. Every test here
     * needs a report to be accepted for any of its assertions to mean anything, so
     * the bucket state is a precondition and is established rather than inherited.
     *
     * <p>The "never reset from a counting test" rule is not breached by this: it
     * forbids discarding click deltas that a test has already produced and is about
     * to assert on, and this runs before the first click of every test in the class.
     */
    @BeforeEach
    void startFromAFullReporterBucket() {
        resetSharedTierState();
    }

    /**
     * A reported link stops redirecting: the click path answers the single 404 and
     * the link is blocked.
     *
     * <p>Demonstrates: AC21.
     */
    @Test
    void aReportedLinkStopsRedirecting() {
        String alice = alice();
        String bob = bob();
        LinkResponse link = givenLink(alice);
        assertEquals(302, api.click(link.code()).statusCode(), "it redirected before the report");

        HttpResponse<String> reported = api.reportAbuse(bob, link.code(), REASON);

        // No evictResolutionCache() here: the takedown has to invalidate, and
        // clearing the cache ourselves would pass against one that never does.
        HttpResponse<String> afterReport = api.click(link.code());
        assertAll(
                () -> assertEquals(202, reported.statusCode(), reported.body()),
                () -> assertEquals(404, afterReport.statusCode(), "a reported link stops redirecting"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, afterReport.body()),
                () -> assertTrue(
                        ApiClient.header(afterReport, Fixtures.LOCATION).isEmpty(),
                        "nothing may still point at the reported target"));
    }

    /**
     * It stops within the published bound, measured from the report response - the
     * same number a delete is held to, because a fraud takedown is the case the
     * bound exists for.
     *
     * <p>Demonstrates: AC9, AC21.
     */
    @Test
    void aReportTakesEffectWithinThePublishedBound() {
        String alice = alice();
        String bob = bob();
        LinkResponse link = givenLink(alice);
        // Clicked first, so whatever cache exists is holding this link when the
        // report arrives - the case the bound is quoted for.
        clickRepeatedly(link.code(), 2);

        assertEquals(202, api.reportAbuse(bob, link.code(), REASON).statusCode());
        Optional<Duration> tookEffectAfter =
                observeUntil(() -> api.click(link.code()).statusCode() == 404, Fixtures.TAKEDOWN_BOUND);

        assertAll(
                () -> assertTrue(
                        tookEffectAfter.isPresent(),
                        "the reported link was still redirecting after the published bound of "
                                + Fixtures.TAKEDOWN_BOUND),
                () -> assertTrue(
                        tookEffectAfter.orElseThrow().compareTo(Fixtures.TAKEDOWN_BOUND) < 0,
                        "it stopped after " + tookEffectAfter.orElse(null)));
    }

    /**
     * A report against a code that was never issued is accepted with the same 202
     * and the same empty body as one against a real link, so the endpoint cannot
     * be used to ask which codes exist.
     *
     * <p>Demonstrates: AC15, AC21.
     */
    @Test
    void aReportAgainstAnUnissuedCodeIsAcceptedLikeAnyOther() {
        String alice = alice();
        String bob = bob();
        LinkResponse realLink = givenLink(alice);

        HttpResponse<String> againstARealLink = api.reportAbuse(bob, realLink.code(), REASON);
        HttpResponse<String> againstAnUnissuedCode = api.reportAbuse(bob, Fixtures.UNISSUED_CODE, REASON);
        HttpResponse<String> againstAMalformedCode = api.reportAbuse(bob, Fixtures.MALFORMED_CODE, REASON);

        assertAll(
                () -> assertEquals(202, againstARealLink.statusCode(), againstARealLink.body()),
                () -> assertEquals(
                        againstARealLink.statusCode(),
                        againstAnUnissuedCode.statusCode(),
                        "a 404 here would make this endpoint an existence oracle"),
                () -> assertEquals(againstARealLink.body(), againstAnUnissuedCode.body()),
                () -> assertEquals("", againstAnUnissuedCode.body(), "the report response carries no body"),
                () -> assertEquals(againstARealLink.statusCode(), againstAMalformedCode.statusCode()),
                () -> assertEquals(againstARealLink.body(), againstAMalformedCode.body()),
                () -> assertNotEquals(404, againstAnUnissuedCode.statusCode()));
    }

    /**
     * A report against another customer's link is accepted and takes it down:
     * anyone signed in may report any link they can name, which is the deliberate
     * trade this design makes and the reason the endpoint is limited per reporter.
     *
     * <p>Demonstrates: AC21, AC9.
     */
    @Test
    void aReportAgainstAnotherCustomersLinkTakesItDown() {
        String alice = alice();
        String bob = bob();
        LinkResponse alicesLink = givenLink(alice);

        // Bob owns nothing here and is refused everything else on this code; he
        // may still report it, and that is enough to take it down.
        HttpResponse<String> reportedByBob = api.reportAbuse(bob, alicesLink.code(), REASON);

        HttpResponse<String> click = api.click(alicesLink.code());
        LinkResponse asItsOwnerSeesIt = ApiClient.asLink(api.getLink(alice, alicesLink.code()));
        assertAll(
                () -> assertEquals(202, reportedByBob.statusCode(), reportedByBob.body()),
                () -> assertEquals(404, click.statusCode(), "another customer's report takes the link down"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, click.body()),
                () -> assertEquals(LinkStatus.BLOCKED, asItsOwnerSeesIt.status()));
    }

    /**
     * A report with no body at all is accepted: the reason is optional, and the
     * reporter and the time are what the record needs.
     *
     * <p>Demonstrates: AC21.
     */
    @Test
    void aReportWithNoBodyIsAccepted() {
        String alice = alice();
        String bob = bob();
        LinkResponse link = givenLink(alice);

        // A null reason sends no body at all, not an empty JSON object.
        HttpResponse<String> withNoBody = api.reportAbuse(bob, link.code(), null);

        assertAll(
                () -> assertEquals(202, withNoBody.statusCode(), withNoBody.body()),
                () -> assertEquals("", withNoBody.body()),
                // The evidence that it was recorded rather than discarded.
                () -> assertEquals(404, api.click(link.code()).statusCode(),
                        "a report with no reason still takes the link down"));
    }

    /**
     * A report body carrying a property the schema does not define, or a reason
     * longer than the maximum, is 400 - and the link is not blocked by a request
     * that was refused.
     *
     * <p>Demonstrates: AC21.
     */
    @Test
    void aMalformedReportBodyIsRejectedAndTakesNothingDown() {
        String alice = alice();
        String bob = bob();
        LinkResponse link = givenLink(alice);
        String atTheLimit = "r".repeat(500);
        String overTheLimit = "r".repeat(501);

        HttpResponse<String> unknownProperty = reportRaw(
                bob, link.code(), "{\"reason\":\"" + REASON + "\",\"blockImmediately\":true}");
        HttpResponse<String> overlongReason = api.reportAbuse(bob, link.code(), overTheLimit);
        HttpResponse<String> notEvenJson = reportRaw(bob, link.code(), "{\"reason\":");

        HttpResponse<String> click = api.click(link.code());
        assertAll(
                () -> assertEquals(400, unknownProperty.statusCode(), unknownProperty.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(unknownProperty).error()),
                () -> assertEquals(400, overlongReason.statusCode(), overlongReason.body()),
                () -> assertEquals("invalid_request", ApiClient.asError(overlongReason).error()),
                () -> assertEquals(400, notEvenJson.statusCode(), notEvenJson.body()),
                () -> assertEquals(302, click.statusCode(), "a refused report blocks nothing"),
                () -> assertEquals(
                        Fixtures.TARGET_URL, ApiClient.header(click, Fixtures.LOCATION).orElse(null)),
                // The boundary itself: 500 characters is inside the documented
                // maximum, so the difference above is the length and not the field.
                () -> assertEquals(202, api.reportAbuse(bob, link.code(), atTheLimit).statusCode(),
                        "the documented maximum reason length is inclusive"));
    }

    /**
     * Reporting without a session is 401 and the link keeps redirecting: an
     * unauthenticated caller cannot take anything down.
     *
     * <p>Demonstrates: AC12, AC21.
     */
    @Test
    void reportingWithoutASessionIsRefusedAndTakesNothingDown() {
        String alice = alice();
        LinkResponse link = givenLink(alice);

        HttpResponse<String> withNoSession = api.reportAbuse(null, link.code(), REASON);
        HttpResponse<String> withAForgedSession = api.reportAbuse(Fixtures.FORGED_BEARER, link.code(), REASON);

        HttpResponse<String> click = api.click(link.code());
        assertAll(
                () -> assertEquals(401, withNoSession.statusCode(), withNoSession.body()),
                () -> assertEquals("unauthorized", ApiClient.asError(withNoSession).error()),
                () -> assertEquals("Authentication required.", ApiClient.asError(withNoSession).message()),
                () -> assertEquals(401, withAForgedSession.statusCode(), withAForgedSession.body()),
                () -> assertEquals(withNoSession.body(), withAForgedSession.body()),
                () -> assertEquals(302, click.statusCode(),
                        "an unauthenticated caller must not be able to take a link down"),
                () -> assertEquals(
                        Fixtures.TARGET_URL, ApiClient.header(click, Fixtures.LOCATION).orElse(null)));
    }

    /**
     * The owner of a reported link still sees it, reported as blocked, with its
     * retained click count - the takedown is visible to them rather than silent.
     *
     * <p>Demonstrates: AC7, AC21.
     */
    @Test
    void theOwnerOfAReportedLinkStillSeesItAndItsCount() {
        String alice = alice();
        String bob = bob();
        LinkResponse link = givenLink(alice);
        clickRepeatedly(link.code(), 3);

        assertEquals(202, api.reportAbuse(bob, link.code(), REASON).statusCode());

        HttpResponse<String> asItsOwnerSeesIt = api.getLink(alice, link.code());
        assertEquals(200, asItsOwnerSeesIt.statusCode(), "a takedown must not make the owner's link vanish");
        LinkResponse blocked = ApiClient.asLink(asItsOwnerSeesIt);
        assertAll(
                () -> assertEquals(link.code(), blocked.code()),
                () -> assertEquals(LinkStatus.BLOCKED, blocked.status(),
                        "the owner is told why it stopped working"),
                () -> assertEquals(Fixtures.TARGET_URL, blocked.longUrl()),
                () -> assertEquals(3L, blocked.clickCount(), "the count it accrued is retained"));
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Posts an arbitrary report body. {@link ApiClient} has no raw form of this
     * call, and the sub-resource path is built from the frozen collection constant
     * rather than spelled out, so a route that moves still moves in one place.
     */
    private HttpResponse<String> reportRaw(String bearer, String code, String jsonBody) {
        return api.send("POST", ApiClient.LINKS_PATH + "/" + code + "/abuse-reports", jsonBody, bearer);
    }
}
