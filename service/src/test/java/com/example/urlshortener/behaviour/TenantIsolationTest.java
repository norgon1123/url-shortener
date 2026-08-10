package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkPage;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.domain.LinkStatus;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * One customer against another's links (AC13, AC15).
 *
 * <p>The individual endpoints each carry their own not-yours case. This class
 * exists because the criterion is stronger than the sum of those: across the
 * whole owner-scoped surface, the answer for another customer's link must be the
 * same answer as for a code that was never issued - one status, one body - and
 * the attempt must leave that link exactly as it was.
 *
 * <p>Checking them together is what catches the endpoint that was added later and
 * answered 403, or 404 with a slightly different body, and so became the oracle
 * every other endpoint was careful not to be.
 *
 * <p>Abuse reporting is in the first test and deliberately not in the second.
 * Reporting is open to any signed-in customer by design, so it is an endpoint
 * whose <em>answer</em> must not distinguish the two cases while its
 * <em>effect</em> is supposed to reach another customer's link; the takedown
 * itself belongs to {@code AbuseReportTest}.
 */
class TenantIsolationTest extends AbstractIntegrationTest {

    /**
     * Fetch, patch, delete and report, aimed at another customer's link and then
     * at a code that was never issued, produce the same answers as each other: no
     * endpoint distinguishes "somebody else's" from "does not exist".
     *
     * <p>Demonstrates: AC13, AC15.
     */
    @Test
    void everyOwnerScopedEndpointAnswersTheSameForAnotherCustomersLinkAsForAnUnissuedCode() {
        String alice = alice();
        String bob = bob();
        LinkResponse alicesLink = givenLink(alice);
        Instant aFutureInstant = Instant.now().plus(90, ChronoUnit.DAYS);

        HttpResponse<String> fetchHers = api.getLink(bob, alicesLink.code());
        HttpResponse<String> fetchNothing = api.getLink(bob, Fixtures.UNISSUED_CODE);
        HttpResponse<String> patchHers = api.updateExpiry(bob, alicesLink.code(), aFutureInstant);
        HttpResponse<String> patchNothing = api.updateExpiry(bob, Fixtures.UNISSUED_CODE, aFutureInstant);
        HttpResponse<String> deleteHers = api.deleteLink(bob, alicesLink.code());
        HttpResponse<String> deleteNothing = api.deleteLink(bob, Fixtures.UNISSUED_CODE);
        // Reporting is last, because unlike the three above it is allowed to have
        // an effect on somebody else's link.
        HttpResponse<String> reportHers = api.reportAbuse(bob, alicesLink.code(), "Phishing");
        HttpResponse<String> reportNothing = api.reportAbuse(bob, Fixtures.UNISSUED_CODE, "Phishing");

        assertAll(
                () -> assertEquals(404, fetchHers.statusCode(), fetchHers.body()),
                () -> assertEquals(fetchNothing.statusCode(), fetchHers.statusCode()),
                () -> assertEquals(fetchNothing.body(), fetchHers.body()),
                () -> assertEquals(404, patchHers.statusCode(), patchHers.body()),
                () -> assertEquals(patchNothing.statusCode(), patchHers.statusCode()),
                () -> assertEquals(patchNothing.body(), patchHers.body()),
                () -> assertNotEquals(409, patchHers.statusCode(),
                        "409 link_not_modifiable is for the caller's own link and would confess ownership"),
                () -> assertEquals(404, deleteHers.statusCode(), deleteHers.body()),
                () -> assertEquals(deleteNothing.statusCode(), deleteHers.statusCode()),
                () -> assertEquals(deleteNothing.body(), deleteHers.body()),
                () -> assertEquals(202, reportHers.statusCode(), reportHers.body()),
                () -> assertEquals(reportNothing.statusCode(), reportHers.statusCode()),
                () -> assertEquals(reportNothing.body(), reportHers.body()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, fetchHers.body(),
                        "one not-found body across the whole owner-scoped surface"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, patchHers.body()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, deleteHers.body()),
                () -> assertTrue(
                        deleteHers.statusCode() != 403
                                && patchHers.statusCode() != 403
                                && fetchHers.statusCode() != 403,
                        "there is no 403 anywhere in this API: it would confirm the code exists"));
    }

    /**
     * After all of those attempts the other customer's link is untouched: same
     * target, same expiry, same status, same click count, and it still redirects.
     * Interference is not only refused, it leaves no trace.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void anotherCustomersLinkIsUnchangedByEveryAttemptOnIt() {
        String alice = alice();
        String bob = bob();
        LinkResponse alicesLink = givenLink(alice, Fixtures.OTHER_TARGET_URL);
        clickRepeatedly(alicesLink.code(), 3);

        api.getLink(bob, alicesLink.code());
        api.updateExpiry(bob, alicesLink.code(), Instant.now().plus(90, ChronoUnit.DAYS));
        api.updateLinkRaw(bob, alicesLink.code(), "{\"longUrl\":\"https://attacker.example.com/\"}");
        api.deleteLink(bob, alicesLink.code());

        LinkResponse afterwards = ApiClient.asLink(api.getLink(alice, alicesLink.code()));
        HttpResponse<String> click = api.click(alicesLink.code());
        assertAll(
                () -> assertEquals(Fixtures.OTHER_TARGET_URL, afterwards.longUrl(),
                        "the target was not repointed"),
                () -> assertEquals(alicesLink.expiresAt(), afterwards.expiresAt(),
                        "the expiry was not moved"),
                () -> assertEquals(LinkStatus.ACTIVE, afterwards.status(), "the link was not taken down"),
                () -> assertEquals(alicesLink.createdAt(), afterwards.createdAt()),
                () -> assertEquals(302, click.statusCode(), "and it still redirects"),
                () -> assertEquals(
                        Fixtures.OTHER_TARGET_URL,
                        ApiClient.header(click, Fixtures.LOCATION).orElse(null)),
                // 3 clicks before the attempts, 1 after: nothing the other customer
                // did counted as a click either.
                () -> assertEquals(4L, reportedClickCount(alice, alicesLink.code()),
                        "the refused attempts moved the count by nothing"));
    }

    /**
     * Ownership is decided by the session and nothing else: the same request with
     * the owner's session succeeds where the other customer's was refused, so the
     * refusals are isolation rather than a broken code.
     *
     * <p>Demonstrates: AC13, AC12.
     */
    @Test
    void theOwnersOwnSessionSucceedsWhereTheOtherCustomersWasRefused() {
        String alice = alice();
        String bob = bob();
        LinkResponse alicesLink = givenLink(alice);
        Instant aFutureInstant = Instant.now().plus(90, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        HttpResponse<String> bobFetching = api.getLink(bob, alicesLink.code());
        HttpResponse<String> bobPatching = api.updateExpiry(bob, alicesLink.code(), aFutureInstant);
        HttpResponse<String> bobDeleting = api.deleteLink(bob, alicesLink.code());

        // The identical requests, differing only in whose session carries them.
        HttpResponse<String> aliceFetching = api.getLink(alice, alicesLink.code());
        HttpResponse<String> alicePatching = api.updateExpiry(alice, alicesLink.code(), aFutureInstant);
        HttpResponse<String> aliceDeleting = api.deleteLink(alice, alicesLink.code());

        assertAll(
                () -> assertEquals(404, bobFetching.statusCode()),
                () -> assertEquals(200, aliceFetching.statusCode(), aliceFetching.body()),
                () -> assertEquals(alicesLink.code(), ApiClient.asLink(aliceFetching).code()),
                () -> assertEquals(404, bobPatching.statusCode()),
                () -> assertEquals(200, alicePatching.statusCode(), alicePatching.body()),
                () -> assertEquals(aFutureInstant, ApiClient.asLink(alicePatching).expiresAt()),
                () -> assertEquals(404, bobDeleting.statusCode()),
                () -> assertEquals(204, aliceDeleting.statusCode(), aliceDeleting.body()));
    }

    /**
     * A customer's list, and the totals reported with it, count only their own
     * links however many the other customer creates.
     *
     * <p>Demonstrates: AC13, AC7.
     */
    @Test
    void aCustomersOwnTotalsAreUnaffectedByAnotherCustomersLinks() {
        String alice = alice();
        String bob = bob();
        long alicesTotalBefore = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();

        for (int i = 0; i < 4; i++) {
            givenLink(bob, Fixtures.TARGET_URL + "&bob=" + i);
        }
        long alicesTotalAfterBobCreated = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();
        givenLink(alice, Fixtures.TARGET_URL + "&alice=1");
        givenLink(alice, Fixtures.TARGET_URL + "&alice=2");

        LinkPage alicesPage = ApiClient.asPage(api.listLinks(alice, 0, 100));
        assertAll(
                () -> assertEquals(alicesTotalBefore, alicesTotalAfterBobCreated,
                        "four links created by another customer changed this customer's total"),
                () -> assertEquals(alicesTotalBefore + 2, alicesPage.totalElements(),
                        "only her own two creations moved it"),
                () -> assertTrue(
                        alicesPage.items().stream().noneMatch(l -> l.longUrl().contains("&bob=")),
                        "and none of the other customer's links appear on her page"));
    }
}
