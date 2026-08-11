package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.LinkPage;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.domain.LinkStatus;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An account somebody made for themselves is as isolated as one that arrived by
 * migration (AC8).
 *
 * <p>The existing suite proves isolation between the two seeded customers. That
 * is not the same claim: seeded ids are fixed, consecutive and present at every
 * boot, and an implementation that keyed a shortcut off them - or that gave a
 * newly created account a null, default or shared tenant - would pass every
 * existing test and fail here. Both directions are exercised, because "cannot
 * see" is a claim about a pair.
 *
 * <p>The last two behaviours are about the one endpoint that has never been
 * owner-scoped: an abuse report names any code and takes that link down. Before
 * this change the set of callers who could do that was two accounts a human had
 * provisioned; after it, it is anybody who can complete a sign-up, and the
 * per-reporter bucket bounds nothing because the reporter id is now free to mint.
 * The eligibility rule - an account may not report a link it does not pre-date
 * unless it is older than {@code app.abuse.min-reporter-age} - is what closes
 * that, and both sides of it are pinned here: the fresh account that must be
 * refused, and the account that must still be able to report, because a takedown
 * path nobody can use is not a fix.
 */
class SignUpIsolationTest extends AbstractIntegrationTest {

    /** An ordinary reason, well inside the documented maximum length. */
    private static final String REASON = "Phishing page imitating a bank sign-in";

    /**
     * How long a link is watched to show a report did <em>not</em> take it down.
     * Long enough that a takedown applied a moment late is still caught, short
     * enough that two behaviours can afford it; the owner's view of the link's
     * status is what makes the claim rather than this wait alone.
     */
    private static final Duration STILL_UP_WINDOW = Duration.ofSeconds(5);

    /**
     * Starts from full buckets. Every behaviour here signs up, signs in and then
     * creates several links, and all three are metered from one client address in
     * a Redis shared by every context in this JVM. A bucket an earlier class
     * drained would answer 429 where these expect 201, and the failure would point
     * at isolation rather than at the throttling that caused it. There are no
     * clicks before this runs, so no delta is discarded.
     */
    @BeforeEach
    void startFromFullBuckets() {
        resetSharedTierState();
    }

    /**
     * A newly created account's link list is empty, and stays empty when other
     * customers create links. A new customer starts owning nothing, however many
     * links exist.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void aNewlyCreatedAccountOwnsNothing() {
        String newcomer = sessionFor(givenAccount());

        LinkPage onCreation = ApiClient.asPage(api.listLinks(newcomer, 0, 20));
        givenLink(alice());
        givenLink(bob());
        LinkPage afterOthersCreated = ApiClient.asPage(api.listLinks(newcomer, 0, 20));

        assertAll(
                () -> assertEquals(List.of(), onCreation.items(),
                        "a new account owns nothing at all"),
                () -> assertEquals(0L, onCreation.totalElements()),
                () -> assertTrue(onCreation.totalPages() <= 1,
                        "no links is at most one empty page: " + onCreation.totalPages()),
                () -> assertEquals(List.of(), afterOthersCreated.items(),
                        "and gains nothing when other customers create links"),
                () -> assertEquals(0L, afterOthersCreated.totalElements()));
    }

    /**
     * A self-signed-up customer naming another customer's code gets 404
     * {@code not_found}, byte-identical to the answer for a code that was never
     * issued - never 403, which would confirm the code exists.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void aSignedUpCustomerCannotReadAnotherCustomersLink() {
        LinkResponse alicesLink = givenLink(alice());
        String newcomer = sessionFor(givenAccount());

        HttpResponse<String> hers = api.getLink(newcomer, alicesLink.code());
        HttpResponse<String> nothing = api.getLink(newcomer, Fixtures.UNISSUED_CODE);

        assertAll(
                () -> assertEquals(404, hers.statusCode(), hers.body()),
                () -> assertEquals(nothing.statusCode(), hers.statusCode()),
                () -> assertEquals(nothing.body(), hers.body(),
                        "somebody else's and never issued are one answer"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, hers.body()),
                () -> assertNotEquals(403, hers.statusCode(),
                        "there is no 403 in this API: it would confirm the code exists"));
    }

    /**
     * The same code answers 404 to a change of expiry from that customer, and the
     * link's expiry is unchanged afterwards.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void aSignedUpCustomerCannotChangeAnotherCustomersLink() {
        String alice = alice();
        LinkResponse alicesLink = givenLink(alice);
        String newcomer = sessionFor(givenAccount());

        HttpResponse<String> patched = api.updateExpiry(
                newcomer, alicesLink.code(), Instant.now().plus(365, ChronoUnit.DAYS));

        LinkResponse asItsOwnerSeesIt = ApiClient.asLink(api.getLink(alice, alicesLink.code()));
        assertAll(
                () -> assertEquals(404, patched.statusCode(), patched.body()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, patched.body()),
                () -> assertNotEquals(409, patched.statusCode(),
                        "link_not_modifiable is for the caller's own link and would confess ownership"),
                () -> assertEquals(alicesLink.expiresAt(), asItsOwnerSeesIt.expiresAt(),
                        "interference is refused and leaves no trace"),
                () -> assertEquals(alicesLink.longUrl(), asItsOwnerSeesIt.longUrl()));
    }

    /**
     * The same code answers 404 to a delete from that customer, and the link keeps
     * redirecting afterwards. A 404 that deleted the row anyway would be the worst
     * of both.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void aSignedUpCustomerCannotDeleteAnotherCustomersLink() {
        LinkResponse alicesLink = givenLink(alice());
        String newcomer = sessionFor(givenAccount());

        HttpResponse<String> deleted = api.deleteLink(newcomer, alicesLink.code());

        HttpResponse<String> clicked = api.click(alicesLink.code());
        assertAll(
                () -> assertEquals(404, deleted.statusCode(), deleted.body()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, deleted.body()),
                () -> assertEquals(302, clicked.statusCode(),
                        "the link is still there and still working: " + clicked.body()),
                () -> assertEquals(Fixtures.TARGET_URL,
                        ApiClient.header(clicked, Fixtures.LOCATION).orElse(null)));
    }

    /**
     * A self-signed-up customer's list contains their own links and nobody else's,
     * across every page - the totals as well as the contents, since a page that
     * hid other customers' rows while counting them would leak how many exist.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void aSignedUpCustomersListContainsOnlyTheirOwnLinks() {
        String alice = alice();
        LinkResponse alicesLink = givenLink(alice);
        String newcomer = sessionFor(givenAccount());
        List<String> theirOwn = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            theirOwn.add(givenLink(newcomer, Fixtures.TARGET_URL + "&mine=" + i).code());
        }

        LinkPage firstPage = ApiClient.asPage(api.listLinks(newcomer, 0, 2));
        List<String> everyPage = everyCodeOwnedBy(newcomer);

        assertAll(
                () -> assertEquals(3L, firstPage.totalElements(),
                        "the total counts their links and nobody else's"),
                () -> assertEquals(2, firstPage.items().size(), "paging still works"),
                () -> assertEquals(2, firstPage.totalPages()),
                () -> assertEquals(3, everyPage.size(), "across every page: " + everyPage),
                () -> assertTrue(everyPage.containsAll(theirOwn), "their own three are all there"),
                () -> assertFalse(everyPage.contains(alicesLink.code()),
                        "and another customer's link is invisible, not merely omitted"));
    }

    /**
     * The other direction: a link created by a self-signed-up customer is invisible
     * to a seeded customer, who gets the same 404 for it and never sees it in a
     * list.
     *
     * <p>Demonstrates: AC8, AC17.
     */
    @Test
    void aSignedUpCustomersLinkIsInvisibleToAnExistingCustomer() {
        String alice = alice();
        String newcomer = sessionFor(givenAccount());
        LinkResponse theirLink = givenLink(newcomer, Fixtures.OTHER_TARGET_URL);

        HttpResponse<String> read = api.getLink(alice, theirLink.code());
        HttpResponse<String> nothing = api.getLink(alice, Fixtures.UNISSUED_CODE);
        HttpResponse<String> deleted = api.deleteLink(alice, theirLink.code());

        List<String> alicesCodes = everyCodeOwnedBy(alice);
        assertAll(
                () -> assertEquals(404, read.statusCode(), read.body()),
                () -> assertEquals(nothing.body(), read.body()),
                () -> assertEquals(404, deleted.statusCode(), deleted.body()),
                () -> assertFalse(alicesCodes.contains(theirLink.code()),
                        "a newcomer's link appears on no page of a seeded customer's list"),
                () -> assertEquals(302, api.click(theirLink.code()).statusCode(),
                        "and it is untouched by the attempt"));
    }

    /**
     * An account created after a link exists cannot get that link taken down. The
     * report is refused, the link keeps redirecting and its owner still sees it as
     * active.
     *
     * <p>This is the control on the hole self-service sign-up opens. The takedown
     * is immediate, permanent and has no unblock path, its only bound is a bucket
     * keyed by reporter, and an attacker who can sign up mints reporters at
     * whatever rate the sign-up bucket allows - so without this rule any published
     * short link can be killed by anybody who can register an address.
     *
     * <p>The refusal is compared against the same caller reporting a code that was
     * never issued, because the no-oracle rule still holds here: whatever this
     * endpoint answers an ineligible reporter, it must answer identically whether
     * or not the code resolves. That comparison is deliberately written as sameness
     * rather than as a status, since the refusal may be a silent 202 or a catalogue
     * error and both satisfy the rule; what may not happen is the link going down.
     *
     * <p>Demonstrates: AC8.
     */
    @Test
    void aSignedUpCustomerCannotTakeDownALinkThatPreDatesTheirAccount() {
        String alice = alice();
        // The link has to exist before the account does: that ordering is the whole
        // condition the rule turns on.
        LinkResponse alicesLink = givenLink(alice);
        String newcomer = sessionFor(givenAccount());

        HttpResponse<String> reported = api.reportAbuse(newcomer, alicesLink.code(), REASON);
        HttpResponse<String> againstAnUnissuedCode =
                api.reportAbuse(newcomer, Fixtures.UNISSUED_CODE, REASON);

        // No evictResolutionCache(): a takedown has to invalidate by itself, so a
        // link that is still redirecting here is a link that was never blocked.
        Optional<Duration> stoppedRedirectingAfter =
                observeUntil(() -> api.click(alicesLink.code()).statusCode() != 302, STILL_UP_WINDOW);
        LinkResponse asItsOwnerSeesIt = ApiClient.asLink(api.getLink(alice, alicesLink.code()));
        assertAll(
                () -> assertTrue(stoppedRedirectingAfter.isEmpty(),
                        "an account younger than the link took it down after "
                                + stoppedRedirectingAfter.orElse(null)),
                () -> assertEquals(Fixtures.TARGET_URL,
                        ApiClient.header(api.click(alicesLink.code()), Fixtures.LOCATION).orElse(null),
                        "and it still points where its owner put it"),
                () -> assertEquals(LinkStatus.ACTIVE, asItsOwnerSeesIt.status(),
                        "the owner's link was not blocked by an account that post-dates it"),
                () -> assertEquals(againstAnUnissuedCode.statusCode(), reported.statusCode(),
                        "the refusal must not depend on whether the code resolves"),
                () -> assertEquals(againstAnUnissuedCode.body(), reported.body(),
                        "nor may its body: this endpoint is not an existence oracle"),
                () -> assertNotEquals(404, reported.statusCode(),
                        "a 404 here would confirm which codes exist"),
                () -> assertTrue(reported.statusCode() < 500,
                        "a refusal is a decision, not a server error: " + reported.statusCode()),
                () -> assertFalse(reported.body().contains(alicesLink.code()),
                        "and it echoes nothing about the link: " + reported.body()));
    }

    /**
     * A customer who signed up before the link existed reports it and it goes down,
     * within the published bound and with the 202 every report gets.
     *
     * <p>The other side of the same rule, and the reason it is a rule about age
     * rather than about how the account arrived: abuse reporting must keep working
     * for accounts that came through sign-up, or the fix for one abuse path has
     * quietly removed the only takedown path a link with no owner has. It also
     * pins that eligibility is not a shortcut keyed off the two seeded ids.
     *
     * <p>Demonstrates: AC8, AC17.
     */
    @Test
    void aSignedUpCustomerCanStillReportALinkTheirAccountPreDates() {
        String reporter = sessionFor(givenAccount());
        String alice = alice();
        LinkResponse alicesLink = givenLink(alice);

        HttpResponse<String> reported = api.reportAbuse(reporter, alicesLink.code(), REASON);

        Optional<Duration> tookEffectAfter = observeUntil(
                () -> api.click(alicesLink.code()).statusCode() == 404, Fixtures.TAKEDOWN_BOUND);
        LinkResponse asItsOwnerSeesIt = ApiClient.asLink(api.getLink(alice, alicesLink.code()));
        assertAll(
                () -> assertEquals(202, reported.statusCode(),
                        "an account that pre-dates the link may report it: " + reported.body()),
                () -> assertEquals("", reported.body(), "the report response carries no body"),
                () -> assertTrue(tookEffectAfter.isPresent(),
                        "the reported link was still redirecting after the published bound of "
                                + Fixtures.TAKEDOWN_BOUND),
                () -> assertEquals(LinkStatus.BLOCKED, asItsOwnerSeesIt.status(),
                        "and its owner is told why it stopped working"));
    }

    // ---- helpers ----------------------------------------------------------

    /** Every code on every page of this caller's list, in page order. */
    private List<String> everyCodeOwnedBy(String bearer) {
        List<String> codes = new ArrayList<>();
        int page = 0;
        while (true) {
            LinkPage listed = ApiClient.asPage(api.listLinks(bearer, page, 100));
            listed.items().forEach(item -> codes.add(item.code()));
            page++;
            if (page >= listed.totalPages()) {
                return codes;
            }
        }
    }
}
