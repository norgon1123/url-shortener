package com.example.urlshortener.behaviour;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.urlshortener.api.AnonymousLinkResponse;
import com.example.urlshortener.api.LinkPage;
import com.example.urlshortener.api.LinkResponse;
import com.example.urlshortener.support.AbstractIntegrationTest;
import com.example.urlshortener.support.ApiClient;
import com.example.urlshortener.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Nobody owns an anonymous link, and the API says so by saying nothing (AC13).
 *
 * <p>Holding the link is the whole of the holder's relationship with the
 * service. Every management surface answers for an anonymous code exactly as it
 * answers for a code that was never issued: 404 {@code not_found}, with the same
 * body, for every caller including whoever created it. Never 403, which would
 * confirm the code exists; never 410, which would confirm it once did.
 *
 * <p>The callers exercised are deliberately three kinds - a seeded customer, a
 * customer created for this test, and no credential at all - because "no caller"
 * is the claim, and an implementation that special-cased one of them would pass a
 * narrower test.
 */
class AnonymousLinkInvisibilityTest extends AbstractIntegrationTest {

    /**
     * Starts from full buckets, for the reason {@code AnonymousLinkCreationTest}
     * gives: the anonymous-create bucket is keyed by client address and another
     * class empties it on purpose, and every behaviour here needs an anonymous
     * link to exist before it can fail to find one. This runs before the first
     * click of each test.
     */
    @BeforeEach
    void startFromFullBuckets() {
        resetSharedTierState();
    }

    /**
     * Reading an anonymous code through the management API answers 404 for a
     * signed-in caller, including one who has just created an anonymous link in
     * this very test.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void readingAnAnonymousCodeAnswersNotFoundForEverySignedInCaller() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();
        String alice = alice();
        String bob = bob();
        // This caller created the link a moment ago, in this process, over this
        // client: the closest thing to "whoever created it" that an account holder
        // can be.
        String theCreatorWithAnAccount = sessionFor(givenAccount());
        AnonymousLinkResponse theirs = givenAnonymousLink(Fixtures.OTHER_TARGET_URL);

        HttpResponse<String> asAlice = api.getLink(alice, anonymous.code());
        HttpResponse<String> asBob = api.getLink(bob, anonymous.code());
        HttpResponse<String> asItsCreator = api.getLink(theCreatorWithAnAccount, theirs.code());

        assertAll(
                () -> assertEquals(404, asAlice.statusCode(), asAlice.body()),
                () -> assertEquals(404, asBob.statusCode(), asBob.body()),
                () -> assertEquals(404, asItsCreator.statusCode(),
                        "creating it buys no read: " + asItsCreator.body()),
                () -> assertNotEquals(403, asAlice.statusCode(),
                        "403 would confirm the code exists"),
                () -> assertNotEquals(410, asItsCreator.statusCode(),
                        "and 410 would confirm it once did"),
                () -> assertEquals(302, api.click(anonymous.code()).statusCode(),
                        "meanwhile the link itself is perfectly alive"));
    }

    /**
     * That 404 is byte-identical to the one an unissued code gets and the one
     * another customer's code gets: unknown, expired, deleted, blocked, somebody
     * else's and nobody's are one answer.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void theNotFoundForAnAnonymousCodeIsByteIdenticalToTheOneForAnUnissuedCode() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();
        String alice = alice();
        LinkResponse bobsLink = givenLink(bob());

        HttpResponse<String> nobodys = api.getLink(alice, anonymous.code());
        HttpResponse<String> neverIssued = api.getLink(alice, Fixtures.UNISSUED_CODE);
        HttpResponse<String> somebodyElses = api.getLink(alice, bobsLink.code());

        assertAll(
                () -> assertEquals(neverIssued.statusCode(), nobodys.statusCode(), nobodys.body()),
                () -> assertEquals(neverIssued.body(), nobodys.body(),
                        "nobody's is not a distinguishable state"),
                () -> assertEquals(somebodyElses.statusCode(), nobodys.statusCode()),
                () -> assertEquals(somebodyElses.body(), nobodys.body(),
                        "and it is not distinguishable from somebody else's either"),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, nobodys.body()),
                () -> assertFalse(ApiClient.asTree(nobodys).has("fields"),
                        "the not-found body carries nothing else: " + nobodys.body()));
    }

    /**
     * Reading an anonymous code with no credential is refused as unauthenticated,
     * exactly as reading any other code is. The management API's 401 comes before
     * any lookup, so it reveals nothing about the code either.
     *
     * <p>Demonstrates: AC13, AC17.
     */
    @Test
    void readingAnAnonymousCodeWithoutASessionIsRefusedAsUnauthenticated() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();

        HttpResponse<String> forTheAnonymousCode = api.getLink(null, anonymous.code());
        HttpResponse<String> forAnUnissuedCode = api.getLink(null, Fixtures.UNISSUED_CODE);

        assertAll(
                () -> assertEquals(401, forTheAnonymousCode.statusCode(),
                        "the management API is authenticated whatever the code is: "
                                + forTheAnonymousCode.body()),
                () -> assertEquals("unauthorized", ApiClient.asError(forTheAnonymousCode).error()),
                () -> assertEquals("Authentication required.",
                        ApiClient.asError(forTheAnonymousCode).message()),
                () -> assertTrue(
                        ApiClient.header(forTheAnonymousCode, Fixtures.WWW_AUTHENTICATE).isPresent(),
                        "with the challenge the contract documents"),
                () -> assertEquals(forAnUnissuedCode.body(), forTheAnonymousCode.body(),
                        "the 401 comes before any lookup, so it says nothing about the code"));
    }

    /**
     * Changing an anonymous link's expiry answers 404 for every caller, and the
     * link is unchanged afterwards - it still redirects and still expires when it
     * said it would.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void changingAnAnonymousLinksExpiryAnswersNotFoundForEveryCaller() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();
        Instant muchLater = Instant.now().plus(365, ChronoUnit.DAYS);

        HttpResponse<String> asAlice = api.updateExpiry(alice(), anonymous.code(), muchLater);
        HttpResponse<String> asANewCustomer =
                api.updateExpiry(sessionFor(givenAccount()), anonymous.code(), muchLater);
        HttpResponse<String> withNoCredential = api.updateExpiry(null, anonymous.code(), muchLater);

        HttpResponse<String> clicked = api.click(anonymous.code());
        assertAll(
                () -> assertEquals(404, asAlice.statusCode(), asAlice.body()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, asAlice.body()),
                () -> assertEquals(404, asANewCustomer.statusCode(), asANewCustomer.body()),
                () -> assertEquals(401, withNoCredential.statusCode(),
                        "and an unauthenticated caller does not get further: "
                                + withNoCredential.body()),
                () -> assertNotEquals(200, asAlice.statusCode(),
                        "there is no way to extend or shorten an anonymous link"),
                () -> assertEquals(302, clicked.statusCode(),
                        "the link is untouched by the attempts: " + clicked.body()),
                () -> assertEquals(Fixtures.TARGET_URL,
                        ApiClient.header(clicked, Fixtures.LOCATION).orElse(null)));
    }

    /**
     * Deleting an anonymous code answers 404 for every caller, and the link keeps
     * redirecting afterwards. Nobody can take one down through this route; the
     * abuse report is the only takedown for an unowned link.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void deletingAnAnonymousCodeAnswersNotFoundAndTakesNothingDown() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();

        HttpResponse<String> asAlice = api.deleteLink(alice(), anonymous.code());
        HttpResponse<String> asBob = api.deleteLink(bob(), anonymous.code());
        HttpResponse<String> asANewCustomer =
                api.deleteLink(sessionFor(givenAccount()), anonymous.code());
        HttpResponse<String> withNoCredential = api.deleteLink(null, anonymous.code());

        HttpResponse<String> clicked = api.click(anonymous.code());
        assertAll(
                () -> assertEquals(404, asAlice.statusCode(), asAlice.body()),
                () -> assertEquals(404, asBob.statusCode(), asBob.body()),
                () -> assertEquals(404, asANewCustomer.statusCode(), asANewCustomer.body()),
                () -> assertEquals(Fixtures.NOT_FOUND_BODY, asAlice.body()),
                () -> assertEquals(401, withNoCredential.statusCode(), withNoCredential.body()),
                () -> assertNotEquals(204, asAlice.statusCode(),
                        "a 204 here would be a takedown route for a link nobody owns"),
                () -> assertEquals(302, clicked.statusCode(),
                        "and the link is still redirecting afterwards: " + clicked.body()));
    }

    /**
     * An anonymous code appears in no customer's link list - not a seeded
     * customer's, not a newly created one's - on any page, and it is counted in no
     * customer's totals. A page that hid the row but counted it would leak that
     * something exists.
     *
     * <p>Demonstrates: AC13.
     */
    @Test
    void anAnonymousCodeAppearsInNoCustomersLinkList() {
        AnonymousLinkResponse anonymous = givenAnonymousLink();
        String alice = alice();
        String bob = bob();
        String newcomer = sessionFor(givenAccount());
        LinkResponse theirOwnLink = givenLink(newcomer);

        List<String> alicesCodes = everyCodeOwnedBy(alice);
        List<String> bobsCodes = everyCodeOwnedBy(bob);
        List<String> newcomersCodes = everyCodeOwnedBy(newcomer);
        LinkPage newcomersFirstPage = ApiClient.asPage(api.listLinks(newcomer, 0, 20));

        assertAll(
                () -> assertAll(List.of(alicesCodes, bobsCodes, newcomersCodes).stream()
                        .map(codes -> (Executable) () -> assertFalse(codes.contains(anonymous.code()),
                                "an anonymous code appears on no page of any customer's list: "
                                        + codes))),
                () -> assertEquals(List.of(theirOwnLink.code()), newcomersCodes,
                        "the newcomer sees the one link they own and nothing else"),
                () -> assertEquals(1L, newcomersFirstPage.totalElements(),
                        "and the total counts only that one - hiding a row while counting it would "
                                + "leak that something exists"));
    }

    /**
     * Creating anonymous links does not change what an existing customer sees:
     * their list, their totals and their own links are exactly as they were. This
     * is the regression surface for rows with no owner arriving in a table every
     * owner-scoped query reads.
     *
     * <p>Demonstrates: AC13, AC17.
     */
    @Test
    void anonymousLinksDoNotDisturbAnExistingCustomersListing() {
        String alice = alice();
        LinkResponse alicesLink = givenLink(alice);
        long totalBefore = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();
        List<String> codesBefore = everyCodeOwnedBy(alice);

        for (int i = 0; i < 3; i++) {
            givenAnonymousLink(Fixtures.OTHER_TARGET_URL + "?n=" + i);
        }

        long totalAfter = ApiClient.asPage(api.listLinks(alice, 0, 1)).totalElements();
        List<String> codesAfter = everyCodeOwnedBy(alice);
        HttpResponse<String> herLinkStillReads = api.getLink(alice, alicesLink.code());
        assertAll(
                () -> assertEquals(totalBefore, totalAfter,
                        "ownerless rows in the table do not enter an owner-scoped count"),
                () -> assertEquals(codesBefore, codesAfter,
                        "nor an owner-scoped page, in content or in order"),
                () -> assertEquals(200, herLinkStillReads.statusCode(), herLinkStillReads.body()),
                () -> assertEquals(alicesLink.longUrl(),
                        ApiClient.asLink(herLinkStillReads).longUrl(),
                        "and her own link is exactly as it was"));
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
